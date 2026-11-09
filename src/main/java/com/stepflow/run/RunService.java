package com.stepflow.run;

import com.stepflow.definition.StepDefinition;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.definition.WorkflowRegistry;
import com.stepflow.domain.StepKind;
import com.stepflow.metrics.StepMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RunService {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final WorkflowRegistry registry;
    private final RunRepository runs;
    private final JsonCodec json;
    private final StepMetrics metrics;

    public RunService(WorkflowRegistry registry, RunRepository runs, JsonCodec json, StepMetrics metrics) {
        this.registry = registry;
        this.runs = runs;
        this.json = json;
        this.metrics = metrics;
    }

    /** The run a start request refers to, and whether this request created it. */
    public record StartResult(WorkflowRun run, boolean created) {
    }

    /**
     * Creates a PENDING run and queues its first step. Later steps are queued one at a
     * time as the step before them succeeds, so the queue only ever holds runnable work.
     */
    @Transactional
    public WorkflowRun start(String workflowType, Map<String, Object> input) {
        return start(workflowType, input, null).run();
    }

    /**
     * Starts a run, or with an idempotency key, returns the run an earlier identical request
     * started. Clients can then safely resend a start request after a timeout without
     * charging a card twice. Reusing a key for a different request is rejected.
     */
    @Transactional
    public StartResult start(String workflowType, Map<String, Object> input, String idempotencyKey) {
        return start(workflowType, input, idempotencyKey, null);
    }

    /**
     * @param startAt when the first step may run, or null for now. The run exists (and shows
     *                as PENDING) straight away, so it can be inspected or cancelled before then.
     */
    @Transactional
    public StartResult start(String workflowType, Map<String, Object> input, String idempotencyKey,
                             Instant startAt) {
        WorkflowDefinition definition = registry.get(workflowType);
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        if (idempotencyKey != null) {
            validateKey(idempotencyKey);
        }

        UUID runId = UUID.randomUUID();
        if (!runs.insertRun(runId, definition.type(), safeInput, idempotencyKey)) {
            WorkflowRun existing = runs.findRunByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Run for key " + idempotencyKey + " vanished"));
            if (!sameRequest(existing, definition.type(), safeInput)) {
                throw new IdempotencyConflictException(idempotencyKey, existing.id());
            }
            return new StartResult(existing, false);
        }

        StepDefinition first = definition.step(0);
        runs.insertStep(runId, 0, first.name(), StepKind.ACTION, first.maxAttempts(), startAt, first.delayOrZero());
        metrics.runStarted(definition.type());
        return new StartResult(get(runId), true);
    }

    private boolean sameRequest(WorkflowRun existing, String workflowType, Map<String, Object> input) {
        // round-trip through JSON so the comparison sees the same types the database stored
        return existing.workflowType().equals(workflowType)
                && Objects.equals(existing.input(), json.read(json.write(input)));
    }

    private static void validateKey(String key) {
        if (key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }

    public WorkflowRun get(UUID runId) {
        return runs.findRun(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    public List<StepExecution> steps(UUID runId) {
        get(runId);
        return runs.findSteps(runId);
    }

    public List<WorkflowRun> recent(int limit) {
        return runs.findRecentRuns(Math.max(1, Math.min(limit, 100)));
    }
}
