package com.stepflow.schedule;

import com.stepflow.run.JsonCodec;
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
public class ScheduleRepository {

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final RowMapper<Schedule> mapper = this::map;

    public ScheduleRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(UUID id, String name, String workflowType, String cron, String zone,
                       Map<String, Object> input, Instant nextFireAt) {
        jdbc.update("""
                INSERT INTO schedules (id, name, workflow_type, cron, zone, input, next_fire_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, name, workflowType, cron, zone, json.write(input), Timestamp.from(nextFireAt));
    }

    public Optional<Schedule> find(UUID id) {
        return jdbc.query("SELECT * FROM schedules WHERE id = ?", mapper, id).stream().findFirst();
    }

    public List<Schedule> findAll() {
        return jdbc.query("SELECT * FROM schedules ORDER BY name", mapper);
    }

    /**
     * Locks up to {@code limit} due schedules for the current transaction. SKIP LOCKED means
     * several instances can run the scheduler and each due schedule still fires once.
     */
    public List<Schedule> lockDue(int limit) {
        return jdbc.query("""
                SELECT * FROM schedules
                 WHERE enabled AND next_fire_at <= now()
                 ORDER BY next_fire_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """, mapper, limit);
    }

    public void recordFired(UUID id, Instant firedFor, UUID runId, Instant nextFireAt) {
        jdbc.update("""
                UPDATE schedules
                   SET last_fired_at = ?, last_run_id = ?, next_fire_at = ?, updated_at = now()
                 WHERE id = ?
                """, Timestamp.from(firedFor), runId, Timestamp.from(nextFireAt), id);
    }

    public boolean setEnabled(UUID id, boolean enabled, Instant nextFireAt) {
        return jdbc.update("""
                UPDATE schedules SET enabled = ?, next_fire_at = COALESCE(?, next_fire_at), updated_at = now()
                 WHERE id = ?
                """, enabled, nextFireAt == null ? null : Timestamp.from(nextFireAt), id) == 1;
    }

    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM schedules WHERE id = ?", id) == 1;
    }

    private Schedule map(ResultSet rs, int rowNum) throws SQLException {
        return new Schedule(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("workflow_type"),
                rs.getString("cron"),
                rs.getString("zone"),
                json.read(rs.getString("input")),
                rs.getBoolean("enabled"),
                instant(rs, "next_fire_at"),
                instant(rs, "last_fired_at"),
                rs.getObject("last_run_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
