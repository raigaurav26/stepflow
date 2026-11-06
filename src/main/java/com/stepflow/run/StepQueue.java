package com.stepflow.run;

import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepKind;
import com.stepflow.domain.StepStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The step_executions table used as a work queue.
 *
 * <p>Workers claim rows with {@code FOR UPDATE SKIP LOCKED}: each worker locks the rows it
 * picks and skips rows another worker already locked, so many workers can poll the same
 * table at once without blocking each other or taking the same step twice.
 *
 * <p>Every write after the claim checks {@code lease_owner}, so a worker whose lease was
 * taken away (for example after a long GC pause) cannot overwrite the new owner's result.
 */
@Repository
public class StepQueue {

    private final JdbcTemplate jdbc;
    private final RunRepository runs;
    private final JsonCodec json;

    public StepQueue(JdbcTemplate jdbc, RunRepository runs, JsonCodec json) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.json = json;
    }

    /** Leases up to {@code limit} runnable steps to {@code workerId} and counts the attempt. */
    public List<StepExecution> claim(String workerId, int limit, Duration lease) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("""
                UPDATE step_executions s
                   SET status = 'LEASED',
                       lease_owner = ?,
                       lease_expires_at = now() + make_interval(secs => ?),
                       attempt = s.attempt + 1,
                       updated_at = now()
                 WHERE s.id IN (
                       SELECT id FROM step_executions
                        WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
                          AND next_run_at <= now()
                        ORDER BY next_run_at
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED)
                RETURNING s.*
                """, runs.stepMapper(), workerId, lease.toMillis() / 1000.0, limit);
    }

    /**
     * Outputs of the run's steps that succeeded (including ones since compensated), in step
     * order, keyed by step name. Compensations read the output of the step they undo here.
     */
    public Map<String, Map<String, Object>> succeededOutputs(UUID runId) {
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        jdbc.query("""
                SELECT step_name, output FROM step_executions
                 WHERE run_id = ? AND kind = 'ACTION' AND status IN ('SUCCEEDED', 'COMPENSATED')
                 ORDER BY step_index
                """, rs -> {
            Map<String, Object> output = json.read(rs.getString("output"));
            outputs.put(rs.getString("step_name"), output == null ? Map.of() : output);
        }, runId);
        return outputs;
    }

    /** @return false if this worker no longer holds the lease, in which case nothing changed */
    public boolean markSucceeded(long stepId, String workerId, Map<String, Object> output) {
        return jdbc.update("""
                UPDATE step_executions
                   SET status = 'SUCCEEDED', output = ?::jsonb, last_error = NULL,
                       lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                 WHERE id = ? AND status = 'LEASED' AND lease_owner = ?
                """, json.write(output), stepId, workerId) == 1;
    }

    /** Puts the step back in the queue to run again at {@code delay} from now. */
    public boolean scheduleRetry(long stepId, String workerId, String error, Duration delay) {
        return jdbc.update("""
                UPDATE step_executions
                   SET status = 'RETRY_SCHEDULED', last_error = ?,
                       next_run_at = now() + make_interval(secs => ?),
                       lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                 WHERE id = ? AND status = 'LEASED' AND lease_owner = ?
                """, error, delay.toMillis() / 1000.0, stepId, workerId) == 1;
    }

    public boolean markDead(long stepId, String workerId, String error) {
        return jdbc.update("""
                UPDATE step_executions
                   SET status = 'DEAD', last_error = ?,
                       lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                 WHERE id = ? AND status = 'LEASED' AND lease_owner = ?
                """, error, stepId, workerId) == 1;
    }

    public void markRunRunning(UUID runId) {
        jdbc.update("""
                UPDATE workflow_runs SET status = 'RUNNING', updated_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, runId);
    }

    public void finishRun(UUID runId, RunStatus status, Map<String, Object> output, String error) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException(status + " is not a final status");
        }
        jdbc.update("""
                UPDATE workflow_runs
                   SET status = ?, output = ?::jsonb, error = ?, completed_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('PENDING', 'RUNNING', 'COMPENSATING')
                """, status.name(), json.write(output), error, runId);
    }

    public void enqueue(UUID runId, int stepIndex, String stepName, int maxAttempts, Duration delay) {
        runs.insertStep(runId, stepIndex, stepName, StepKind.ACTION, maxAttempts, null, delay);
    }

    public void enqueueCompensation(UUID runId, int stepIndex, String stepName, int maxAttempts) {
        runs.insertStep(runId, stepIndex, stepName, StepKind.COMPENSATION, maxAttempts);
    }

    /** Indexes of the run's succeeded (not yet compensated) steps below {@code index}, highest first. */
    public List<Integer> succeededStepsBelow(UUID runId, int index) {
        return jdbc.queryForList("""
                SELECT step_index FROM step_executions
                 WHERE run_id = ? AND kind = 'ACTION' AND status = 'SUCCEEDED' AND step_index < ?
                 ORDER BY step_index DESC
                """, Integer.class, runId, index);
    }

    public void markCompensated(UUID runId, int stepIndex) {
        jdbc.update("""
                UPDATE step_executions SET status = 'COMPENSATED', updated_at = now()
                 WHERE run_id = ? AND step_index = ? AND kind = 'ACTION' AND status = 'SUCCEEDED'
                """, runId, stepIndex);
    }

    public void markRunCompensating(UUID runId, String error) {
        jdbc.update("""
                UPDATE workflow_runs SET status = 'COMPENSATING', error = ?, updated_at = now()
                 WHERE id = ? AND status = 'RUNNING'
                """, error, runId);
    }

    public StepStatus statusOf(long stepId) {
        return StepStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM step_executions WHERE id = ?", String.class, stepId));
    }
}
