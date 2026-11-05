package com.stepflow.run;

import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepKind;
import com.stepflow.domain.StepStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    private final RowMapper<WorkflowRun> runMapper;
    private final RowMapper<StepExecution> stepMapper;

    public RunRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
        this.runMapper = this::mapRun;
        this.stepMapper = this::mapStep;
    }

    /**
     * Inserts the run unless another run already holds the same idempotency key.
     *
     * @return true if this call created the run
     */
    public boolean insertRun(UUID id, String workflowType, Map<String, Object> input, String idempotencyKey) {
        // ON CONFLICT instead of check-then-insert: two requests racing with the same key
        // can't both pass a check, and a unique violation would abort the transaction
        return jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_type, status, input, idempotency_key)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, id, workflowType, RunStatus.PENDING.name(), json.write(input), idempotencyKey) == 1;
    }

    public Optional<WorkflowRun> findRunByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM workflow_runs WHERE idempotency_key = ?", runMapper, idempotencyKey)
                .stream().findFirst();
    }

    public void insertStep(UUID runId, int stepIndex, String stepName, int maxAttempts) {
        insertStep(runId, stepIndex, stepName, StepKind.ACTION, maxAttempts);
    }

    public void insertStep(UUID runId, int stepIndex, String stepName, StepKind kind, int maxAttempts) {
        jdbc.update("""
                INSERT INTO step_executions (run_id, step_index, step_name, kind, status, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?)
                """, runId, stepIndex, stepName, kind.name(), StepStatus.PENDING.name(), maxAttempts);
    }

    public Optional<WorkflowRun> findRun(UUID id) {
        return jdbc.query("SELECT * FROM workflow_runs WHERE id = ?", runMapper, id)
                .stream().findFirst();
    }

    public List<WorkflowRun> findRecentRuns(int limit) {
        return jdbc.query("SELECT * FROM workflow_runs ORDER BY created_at DESC LIMIT ?", runMapper, limit);
    }

    public List<StepExecution> findSteps(UUID runId) {
        return jdbc.query("SELECT * FROM step_executions WHERE run_id = ? ORDER BY id",
                stepMapper, runId);
    }

    RowMapper<StepExecution> stepMapper() {
        return stepMapper;
    }

    private WorkflowRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRun(
                rs.getObject("id", UUID.class),
                rs.getString("workflow_type"),
                RunStatus.valueOf(rs.getString("status")),
                json.read(rs.getString("input")),
                json.read(rs.getString("output")),
                rs.getString("error"),
                rs.getString("idempotency_key"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "completed_at"));
    }

    private StepExecution mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new StepExecution(
                rs.getLong("id"),
                rs.getObject("run_id", UUID.class),
                rs.getInt("step_index"),
                rs.getString("step_name"),
                StepKind.valueOf(rs.getString("kind")),
                StepStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                json.read(rs.getString("output")),
                rs.getString("last_error"),
                instant(rs, "next_run_at"),
                rs.getString("lease_owner"),
                instant(rs, "lease_expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
