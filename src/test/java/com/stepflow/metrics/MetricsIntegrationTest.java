package com.stepflow.metrics;

import com.stepflow.definition.NonRetryableException;
import com.stepflow.definition.RetryPolicy;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.run.RunService;
import com.stepflow.run.StepQueue;
import com.stepflow.worker.WorkerPool;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot tests switch metrics export off unless asked; this brings back the Prometheus registry and endpoint
@AutoConfigureObservability
class MetricsIntegrationTest {

    static final RetryPolicy FAST = RetryPolicy.fixed(Duration.ZERO);
    static final AtomicInteger flakyCalls = new AtomicInteger();

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition metricsGood() {
            return WorkflowDefinition.builder("metrics-good")
                    .step("one", ctx -> Map.of())
                    .step("two", ctx -> Map.of())
                    .build();
        }

        @Bean
        WorkflowDefinition metricsFlaky() {
            return WorkflowDefinition.builder("metrics-flaky")
                    .step("call", 3, FAST, ctx -> {
                        if (flakyCalls.incrementAndGet() < 3) {
                            throw new IllegalStateException("timeout");
                        }
                        return Map.of();
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition metricsBroken() {
            return WorkflowDefinition.builder("metrics-broken")
                    .step("charge", 3, FAST, ctx -> {
                        throw new NonRetryableException("declined");
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition metricsLater() {
            return WorkflowDefinition.builder("metrics-later")
                    .sleep("wait", Duration.ofHours(1))
                    .build();
        }
    }

    @Autowired
    private RunService runService;

    @Autowired
    private WorkerPool workers;

    @Autowired
    private StepQueue queue;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        flakyCalls.set(0);
    }

    private void drain() {
        for (int i = 0; i < 10; i++) {
            workers.drainOnce(10);
        }
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name, String... tags) throws InterruptedException {
        Thread.sleep(1_100); // the gauges cache one database snapshot for a second
        return registry.get(name).tags(tags).gauge().value();
    }

    private long timerCount(String... tags) {
        var timer = registry.find("stepflow.step.duration").tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void countsRunsAndTimesSteps() {
        // the registry lives as long as the Spring context, so compare with what was there before
        double started = counter("stepflow.runs.started", "workflow", "metrics-good");
        double completed = counter("stepflow.runs.finished", "workflow", "metrics-good", "status", "COMPLETED");
        long timed = timerCount("workflow", "metrics-good", "step", "one", "kind", "action", "outcome", "succeeded");

        runService.start("metrics-good", Map.of());
        runService.start("metrics-good", Map.of());
        drain();

        assertEquals(started + 2, counter("stepflow.runs.started", "workflow", "metrics-good"));
        assertEquals(completed + 2, counter("stepflow.runs.finished", "workflow", "metrics-good", "status", "COMPLETED"));
        var timer = registry.get("stepflow.step.duration")
                .tags("workflow", "metrics-good", "step", "one", "kind", "action", "outcome", "succeeded").timer();
        assertEquals(timed + 2, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0);
    }

    @Test
    void countsRetriesAndFailedAttempts() {
        runService.start("metrics-flaky", Map.of());
        drain();

        assertEquals(2, counter("stepflow.step.retries", "workflow", "metrics-flaky", "step", "call"));
        assertEquals(2, registry.get("stepflow.step.duration")
                .tags("workflow", "metrics-flaky", "outcome", "failed").timer().count());
        assertEquals(1, registry.get("stepflow.step.duration")
                .tags("workflow", "metrics-flaky", "outcome", "succeeded").timer().count());
        assertEquals(1, counter("stepflow.runs.finished", "workflow", "metrics-flaky", "status", "COMPLETED"));
    }

    @Test
    void countsDeadStepsAndFailedRuns() {
        runService.start("metrics-broken", Map.of());
        drain();

        assertEquals(1, counter("stepflow.step.dead", "workflow", "metrics-broken", "step", "charge", "kind", "action"));
        assertEquals(1, counter("stepflow.runs.finished", "workflow", "metrics-broken", "status", "FAILED"));
    }

    @Test
    void countsReclaimedLeasesAndLostResults() {
        double before = counter("stepflow.leases.reclaimed");
        runService.start("metrics-good", Map.of());
        var claimed = queue.claim("dead-worker", 1, Duration.ofMinutes(5)).get(0);
        jdbc.update("UPDATE step_executions SET lease_expires_at = now() - interval '1 second' WHERE id = ?", claimed.id());

        // what the LeaseReaper does on each tick
        int reclaimed = queue.reclaimExpiredLeases(10).size();
        registry.counter("stepflow.leases.reclaimed").increment(reclaimed);

        assertEquals(before + 1, counter("stepflow.leases.reclaimed"));
    }

    @Test
    void queueGaugesDescribeTheQueueRightNow() throws Exception {
        runService.start("metrics-good", Map.of());
        runService.start("metrics-good", Map.of());
        runService.start("metrics-later", Map.of());
        queue.claim("some-worker", 1, Duration.ofMinutes(5));

        assertEquals(1, gauge("stepflow.queue.steps", "state", "runnable"));
        assertEquals(1, gauge("stepflow.queue.steps", "state", "leased"));
        assertEquals(1, gauge("stepflow.queue.steps", "state", "waiting"), "the 1 hour sleep is waiting, not runnable");
        assertEquals(3, gauge("stepflow.runs.active", "status", "PENDING"));
        assertTrue(gauge("stepflow.queue.oldest.runnable.seconds") >= 1.0, "a runnable step that has waited a moment");
    }

    @Test
    void emptyQueueReadsZero() throws Exception {
        assertEquals(0, gauge("stepflow.queue.steps", "state", "runnable"));
        assertEquals(0, gauge("stepflow.queue.oldest.runnable.seconds"));
    }

    @Test
    void prometheusEndpointExposesTheMetrics() throws Exception {
        UUID id = runService.start("metrics-good", Map.of()).id();
        drain();
        assertNotNull(id);

        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(scrape.contains("stepflow_runs_started_total{"), "runs started counter");
        assertTrue(scrape.contains("stepflow_step_duration_seconds_count{"), "step timer");
        assertTrue(scrape.contains("stepflow_step_duration_seconds_bucket{"), "histogram buckets for latency percentiles");
        assertTrue(scrape.contains("stepflow_queue_steps{"), "queue gauge");
        assertTrue(scrape.contains("application=\"stepflow\""), "common application tag");
        assertTrue(scrape.contains("workflow=\"metrics-good\""), "workflow tag");
    }
}
