package com.stepflow.worker;

import com.stepflow.definition.StepContext;
import com.stepflow.definition.StepDefinition;
import com.stepflow.definition.UnknownWorkflowException;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.definition.WorkflowRegistry;
import com.stepflow.domain.RunStatus;
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

/** Runs one leased step and records what happened. */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final WorkflowRegistry registry;
    private final RunService runService;
    private final StepQueue queue;
    private final TransactionTemplate tx;

    public StepExecutor(WorkflowRegistry registry, RunService runService, StepQueue queue, TransactionTemplate tx) {
        this.registry = registry;
        this.runService = runService;
        this.queue = queue;
        this.tx = tx;
    }

    public void execute(StepExecution step, String workerId) {
        WorkflowRun run = runService.get(step.runId());
        WorkflowDefinition workflow;
        try {
            workflow = registry.get(run.workflowType());
        } catch (UnknownWorkflowException e) {
            // the code for this workflow is gone (renamed or removed), so retrying can't help
            giveUp(step, workerId, e.getMessage());
            return;
        }
        queue.markRunRunning(run.id());

        StepDefinition definition = workflow.step(step.stepIndex());
        StepContext context = new StepContext(run.id(), run.input(), queue.succeededOutputs(run.id()), step.attempt());

        Map<String, Object> output;
        try {
            output = definition.handler().execute(context);
        } catch (Exception e) {
            onFailure(step, workerId, describe(e));
            return;
        }
        onSuccess(workflow, step, workerId, output == null ? Map.of() : output);
    }

    private void onSuccess(WorkflowDefinition workflow, StepExecution step, String workerId,
                           Map<String, Object> output) {
        tx.executeWithoutResult(status -> {
            if (!queue.markSucceeded(step.id(), workerId, output)) {
                log.warn("Lost the lease on step {} of run {}, dropping its result", step.stepName(), step.runId());
                return;
            }
            int next = step.stepIndex() + 1;
            if (next < workflow.steps().size()) {
                StepDefinition nextStep = workflow.step(next);
                queue.enqueue(step.runId(), next, nextStep.name(), nextStep.maxAttempts());
            } else {
                queue.finishRun(step.runId(), RunStatus.COMPLETED, output, null);
            }
        });
    }

    private void onFailure(StepExecution step, String workerId, String error) {
        if (step.attempt() < step.maxAttempts()) {
            log.info("Step {} of run {} failed on attempt {}/{}: {}",
                    step.stepName(), step.runId(), step.attempt(), step.maxAttempts(), error);
            queue.scheduleRetry(step.id(), workerId, error, Duration.ZERO);
        } else {
            giveUp(step, workerId, error);
        }
    }

    private void giveUp(StepExecution step, String workerId, String error) {
        log.warn("Step {} of run {} is dead after {} attempt(s): {}",
                step.stepName(), step.runId(), step.attempt(), error);
        tx.executeWithoutResult(status -> {
            if (queue.markDead(step.id(), workerId, error)) {
                queue.finishRun(step.runId(), RunStatus.FAILED, null,
                        "Step '" + step.stepName() + "' failed: " + error);
            }
        });
    }

    private static String describe(Exception e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
