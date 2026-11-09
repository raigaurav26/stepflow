package com.stepflow.worker;

import com.stepflow.definition.NonRetryableException;
import com.stepflow.definition.RetryPolicy;
import com.stepflow.definition.StepContext;
import com.stepflow.definition.StepDefinition;
import com.stepflow.definition.StepHandler;
import com.stepflow.definition.UnknownWorkflowException;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.definition.WorkflowRegistry;
import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepKind;
import com.stepflow.metrics.StepMetrics;
import com.stepflow.run.RunService;
import com.stepflow.run.StepExecution;
import com.stepflow.run.StepQueue;
import com.stepflow.run.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Runs one leased step and records what happened. */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final WorkflowRegistry registry;
    private final RunService runService;
    private final StepQueue queue;
    private final TransactionTemplate tx;
    private final RetryPolicy defaultRetryPolicy;
    private final StepMetrics metrics;

    public StepExecutor(WorkflowRegistry registry, RunService runService, StepQueue queue,
                        TransactionTemplate tx, RetryProperties retryProperties, StepMetrics metrics) {
        this.metrics = metrics;
        this.registry = registry;
        this.runService = runService;
        this.queue = queue;
        this.tx = tx;
        this.defaultRetryPolicy = retryProperties.toPolicy();
    }

    public void execute(StepExecution step, String workerId) {
        WorkflowRun run = runService.get(step.runId());
        WorkflowDefinition workflow;
        try {
            workflow = registry.get(run.workflowType());
        } catch (UnknownWorkflowException e) {
            // the code for this workflow is gone (renamed or removed), so retrying or undoing can't happen
            failRun(run.workflowType(), step, workerId, e.getMessage(), e.getMessage());
            return;
        }
        queue.markRunRunning(run.id());

        if (step.attempt() > step.maxAttempts()) {
            // the last allowed attempt was lost with a crashed worker; don't run it again
            outOfAttempts(workflow, run, step, workerId, step.lastError() == null
                    ? "Out of attempts" : step.lastError());
            return;
        }

        StepDefinition definition = workflow.step(step.stepIndex());
        boolean compensating = step.kind() == StepKind.COMPENSATION;
        StepHandler handler = compensating ? definition.compensation() : definition.handler();
        StepContext context = new StepContext(run.id(), run.input(), queue.succeededOutputs(run.id()), step.attempt());

        Map<String, Object> output;
        long startedAt = System.nanoTime();
        try {
            output = handler.execute(context);
        } catch (NonRetryableException e) {
            metrics.stepRan(run.workflowType(), step.stepName(), step.kind().name(), "failed", since(startedAt));
            outOfAttempts(workflow, run, step, workerId, describe(e));
            return;
        } catch (Exception e) {
            metrics.stepRan(run.workflowType(), step.stepName(), step.kind().name(), "failed", since(startedAt));
            onFailure(workflow, run, definition, step, workerId, describe(e));
            return;
        }
        metrics.stepRan(run.workflowType(), step.stepName(), step.kind().name(), "succeeded", since(startedAt));
        output = output == null ? Map.of() : output;
        if (compensating) {
            onCompensated(workflow, run, step, workerId, output);
        } else {
            onSuccess(workflow, run, step, workerId, output);
        }
    }

    private static Duration since(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    private void onSuccess(WorkflowDefinition workflow, WorkflowRun run, StepExecution step, String workerId,
                           Map<String, Object> output) {
        tx.executeWithoutResult(status -> {
            if (!queue.markSucceeded(step.id(), workerId, output)) {
                log.warn("Lost the lease on step {} of run {}, dropping its result", step.stepName(), step.runId());
                metrics.leaseLost(run.workflowType(), step.stepName());
                return;
            }
            int next = step.stepIndex() + 1;
            if (next < workflow.steps().size()) {
                StepDefinition nextStep = workflow.step(next);
                queue.enqueue(step.runId(), next, nextStep.name(), nextStep.maxAttempts(), nextStep.delayOrZero());
            } else {
                finish(run, RunStatus.COMPLETED, output, null);
            }
        });
    }

    private void finish(WorkflowRun run, RunStatus status, Map<String, Object> output, String error) {
        if (queue.finishRun(run.id(), status, output, error)) {
            metrics.runFinished(run.workflowType(), status);
        }
    }

    private void onFailure(WorkflowDefinition workflow, WorkflowRun run, StepDefinition definition,
                           StepExecution step, String workerId, String error) {
        if (step.attempt() < step.maxAttempts()) {
            Duration delay = definition.retryPolicyOr(defaultRetryPolicy)
                    .delayAfter(step.attempt(), ThreadLocalRandom.current());
            log.info("{} {} of run {} failed on attempt {}/{}, retrying in {} ms: {}", label(step),
                    step.stepName(), step.runId(), step.attempt(), step.maxAttempts(), delay.toMillis(), error);
            if (queue.scheduleRetry(step.id(), workerId, error, delay)) {
                metrics.stepRetried(run.workflowType(), step.stepName());
            }
        } else {
            outOfAttempts(workflow, run, step, workerId, error);
        }
    }

    private void outOfAttempts(WorkflowDefinition workflow, WorkflowRun run, StepExecution step,
                               String workerId, String error) {
        log.warn("{} {} of run {} is dead after {} attempt(s): {}", label(step),
                step.stepName(), step.runId(), step.attempt(), error);
        if (step.kind() == StepKind.COMPENSATION) {
            // an undo that keeps failing needs a human: stop and leave the evidence on the run
            failRun(run.workflowType(), step, workerId, error, "Compensation of step '" + step.stepName() + "' failed: " + error
                    + " (run was rolling back after: " + run.error() + ")");
            return;
        }
        String runError = "Step '" + step.stepName() + "' failed: " + error;
        tx.executeWithoutResult(status -> {
            if (!queue.markDead(step.id(), workerId, error)) {
                return;
            }
            metrics.stepDead(run.workflowType(), step.stepName(), step.kind().name());
            OptionalInt toUndo = nextToCompensate(workflow, step.runId(), step.stepIndex());
            if (toUndo.isEmpty()) {
                finish(run, RunStatus.FAILED, null, runError);
                return;
            }
            log.info("Rolling back run {} after {} failed", step.runId(), step.stepName());
            queue.markRunCompensating(step.runId(), runError);
            enqueueCompensation(workflow, step.runId(), toUndo.getAsInt());
        });
    }

    private void onCompensated(WorkflowDefinition workflow, WorkflowRun run, StepExecution step, String workerId,
                               Map<String, Object> output) {
        tx.executeWithoutResult(status -> {
            if (!queue.markSucceeded(step.id(), workerId, output)) {
                log.warn("Lost the lease on compensation {} of run {}", step.stepName(), step.runId());
                metrics.leaseLost(run.workflowType(), step.stepName());
                return;
            }
            queue.markCompensated(step.runId(), step.stepIndex());
            OptionalInt toUndo = nextToCompensate(workflow, step.runId(), step.stepIndex());
            if (toUndo.isPresent()) {
                enqueueCompensation(workflow, step.runId(), toUndo.getAsInt());
            } else {
                finish(run, RunStatus.COMPENSATED, null, run.error());
            }
        });
    }

    /** The highest succeeded step below {@code index} that has something to undo. */
    private OptionalInt nextToCompensate(WorkflowDefinition workflow, UUID runId, int index) {
        return queue.succeededStepsBelow(runId, index).stream()
                .mapToInt(Integer::intValue)
                .filter(i -> workflow.step(i).hasCompensation())
                .findFirst();
    }

    private void enqueueCompensation(WorkflowDefinition workflow, UUID runId, int index) {
        StepDefinition step = workflow.step(index);
        queue.enqueueCompensation(runId, index, step.name(), step.maxAttempts());
    }

    private void failRun(String workflowType, StepExecution step, String workerId, String stepError,
                         String runError) {
        tx.executeWithoutResult(status -> {
            if (queue.markDead(step.id(), workerId, stepError)) {
                metrics.stepDead(workflowType, step.stepName(), step.kind().name());
                if (queue.finishRun(step.runId(), RunStatus.FAILED, null, runError)) {
                    metrics.runFinished(workflowType, RunStatus.FAILED);
                }
            }
        });
    }

    private static String label(StepExecution step) {
        return step.kind() == StepKind.COMPENSATION ? "Compensation of step" : "Step";
    }

    private static String describe(Exception e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
