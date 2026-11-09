package com.stepflow.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * Gauges that describe the queue as it is right now, read from the database, so every
 * instance reports the same numbers for the whole system.
 *
 * <pre>
 * stepflow_queue_steps{state="runnable"}   ready to run now: if this grows, add workers
 * stepflow_queue_steps{state="waiting"}    scheduled for later: retry backoff, sleeps, delayed starts
 * stepflow_queue_steps{state="leased"}     being run by a worker
 * stepflow_queue_oldest_runnable_seconds   how long the longest-waiting runnable step has waited
 * stepflow_runs_active{status}             runs still in progress
 * </pre>
 *
 * One query serves all gauges and is cached for a second, so a scrape costs one query however
 * many gauges there are, and several scrapers don't multiply the load.
 */
@Component
public class QueueGauges {

    private static final long CACHE_MILLIS = 1_000;

    private record Snapshot(long takenAt, double runnable, double waiting, double leased, double oldestSeconds,
                            double pending, double running, double compensating) {
    }

    private final JdbcTemplate jdbc;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>(new Snapshot(0, 0, 0, 0, 0, 0, 0, 0));

    public QueueGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        queue(registry, "runnable", Snapshot::runnable);
        queue(registry, "waiting", Snapshot::waiting);
        queue(registry, "leased", Snapshot::leased);
        Gauge.builder("stepflow.queue.oldest.runnable.seconds", () -> read(Snapshot::oldestSeconds))
                .description("How long the longest-waiting runnable step has been waiting")
                .baseUnit("seconds").register(registry);
        active(registry, "PENDING", Snapshot::pending);
        active(registry, "RUNNING", Snapshot::running);
        active(registry, "COMPENSATING", Snapshot::compensating);
    }

    private void queue(MeterRegistry registry, String state, ToDoubleFunction<Snapshot> field) {
        Gauge.builder("stepflow.queue.steps", () -> read(field)).tag("state", state)
                .description("Steps in the queue by state").register(registry);
    }

    private void active(MeterRegistry registry, String status, ToDoubleFunction<Snapshot> field) {
        Gauge.builder("stepflow.runs.active", () -> read(field)).tag("status", status)
                .description("Runs that have not finished").register(registry);
    }

    private double read(ToDoubleFunction<Snapshot> field) {
        return field.applyAsDouble(snapshot());
    }

    private Snapshot snapshot() {
        Snapshot current = cached.get();
        long now = System.currentTimeMillis();
        if (now - current.takenAt() < CACHE_MILLIS) {
            return current;
        }
        try {
            Snapshot fresh = query(now);
            cached.set(fresh);
            return fresh;
        } catch (RuntimeException e) {
            return current; // database trouble must not break the scrape; keep the last numbers
        }
    }

    private Snapshot query(long now) {
        return jdbc.query("""
                SELECT
                  count(*) FILTER (WHERE s.status IN ('PENDING', 'RETRY_SCHEDULED') AND s.next_run_at <= now()) AS runnable,
                  count(*) FILTER (WHERE s.status IN ('PENDING', 'RETRY_SCHEDULED') AND s.next_run_at > now()) AS waiting,
                  count(*) FILTER (WHERE s.status = 'LEASED') AS leased,
                  COALESCE(EXTRACT(EPOCH FROM now() - min(s.next_run_at)
                      FILTER (WHERE s.status IN ('PENDING', 'RETRY_SCHEDULED') AND s.next_run_at <= now())), 0) AS oldest,
                  (SELECT count(*) FROM workflow_runs WHERE status = 'PENDING') AS pending,
                  (SELECT count(*) FROM workflow_runs WHERE status = 'RUNNING') AS running,
                  (SELECT count(*) FROM workflow_runs WHERE status = 'COMPENSATING') AS compensating
                  FROM step_executions s
                 WHERE s.status IN ('PENDING', 'RETRY_SCHEDULED', 'LEASED')
                """, rs -> {
            rs.next();
            return new Snapshot(now, rs.getDouble("runnable"), rs.getDouble("waiting"), rs.getDouble("leased"),
                    rs.getDouble("oldest"), rs.getDouble("pending"), rs.getDouble("running"),
                    rs.getDouble("compensating"));
        });
    }
}
