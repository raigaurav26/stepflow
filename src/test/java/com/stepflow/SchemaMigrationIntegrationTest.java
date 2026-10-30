package com.stepflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the Flyway migrations against a real PostgreSQL and checks the constraints hold. */
@SpringBootTest
class SchemaMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationsCreateBothTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertTrue(tables.containsAll(List.of("workflow_runs", "step_executions")), tables.toString());
    }

    @Test
    void rejectsUnknownStatus() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO workflow_runs (id, workflow_type, status) VALUES (?, 'demo', 'EXPLODED')",
                UUID.randomUUID()));
    }

    @Test
    void idempotencyKeyIsUnique() {
        String key = "order-" + UUID.randomUUID();
        String sql = "INSERT INTO workflow_runs (id, workflow_type, status, idempotency_key) "
                + "VALUES (?, 'demo', 'PENDING', ?)";
        jdbc.update(sql, UUID.randomUUID(), key);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, UUID.randomUUID(), key));
    }

    @Test
    void stepIndexIsUniqueWithinARun() {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_runs (id, workflow_type, status) VALUES (?, 'demo', 'PENDING')", runId);
        String sql = "INSERT INTO step_executions (run_id, step_index, step_name, status) "
                + "VALUES (?, 0, 'reserve', 'PENDING')";
        jdbc.update(sql, runId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, runId));
    }
}
