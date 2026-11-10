package com.stepflow.api;

import com.stepflow.domain.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OverviewService {

    private final JdbcTemplate jdbc;

    public OverviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Overview overview() {
        Map<String, Long> runs = zeroed();
        jdbc.query("SELECT status, count(*) AS n FROM workflow_runs GROUP BY status",
                rs -> {
                    runs.put(rs.getString("status"), rs.getLong("n"));
                });

        Map<String, Long> finished = new LinkedHashMap<>();
        for (RunStatus status : RunStatus.values()) {
            if (status.isTerminal()) {
                finished.put(status.name(), 0L);
            }
        }
        jdbc.query("""
                SELECT status, count(*) AS n FROM workflow_runs
                 WHERE completed_at > now() - interval '1 hour' GROUP BY status
                """, rs -> {
            finished.put(rs.getString("status"), rs.getLong("n"));
        });

        return jdbc.query("""
                SELECT
                  count(*) FILTER (WHERE status IN ('PENDING', 'RETRY_SCHEDULED') AND next_run_at <= now()) AS runnable,
                  count(*) FILTER (WHERE status IN ('PENDING', 'RETRY_SCHEDULED') AND next_run_at > now()) AS waiting,
                  count(*) FILTER (WHERE status = 'LEASED') AS leased,
                  COALESCE(EXTRACT(EPOCH FROM now() - min(next_run_at)
                      FILTER (WHERE status IN ('PENDING', 'RETRY_SCHEDULED') AND next_run_at <= now())), 0) AS oldest,
                  (SELECT count(*) FROM schedules WHERE enabled) AS schedules_on,
                  (SELECT count(*) FROM schedules WHERE NOT enabled) AS schedules_off
                  FROM step_executions
                 WHERE status IN ('PENDING', 'RETRY_SCHEDULED', 'LEASED')
                """, rs -> {
            rs.next();
            Map<String, Long> schedules = new LinkedHashMap<>();
            schedules.put("enabled", rs.getLong("schedules_on"));
            schedules.put("paused", rs.getLong("schedules_off"));
            return new Overview(runs, finished, rs.getLong("runnable"), rs.getLong("waiting"), rs.getLong("leased"),
                    Math.round(rs.getDouble("oldest") * 10) / 10.0, schedules);
        });
    }

    private static Map<String, Long> zeroed() {
        Map<String, Long> runs = new LinkedHashMap<>();
        for (RunStatus status : RunStatus.values()) {
            runs.put(status.name(), 0L);
        }
        return runs;
    }
}
