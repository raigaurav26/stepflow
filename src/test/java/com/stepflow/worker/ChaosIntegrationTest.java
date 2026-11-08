package com.stepflow.worker;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepStatus;
import com.stepflow.run.RunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos tests: workers "die" in the middle of steps, and every run must still finish.
 *
 * <p>A crash is simulated by throwing an {@link Error} from the handler. The executor only
 * catches {@code Exception}, so nothing is written back, the worker thread's heartbeat stops
 * and the lease is left to expire, exactly what a killed process leaves behind.
 */
@SpringBootTest(properties = {
        "stepflow.worker.enabled=true",
        "stepflow.worker.threads=6",
        "stepflow.worker.poll-interval=20ms",
        "stepflow.worker.lease-duration=600ms",
        "stepflow.worker.reap-interval=200ms"
})
// close the context afterwards: its live workers would otherwise steal steps from tests that share the database
@DirtiesContext
class ChaosIntegrationTest {

    static class WorkerCrash extends Error {
        WorkerCrash(String message) {
            super(message, null, false, false);
        }
    }

    static final Random random = new Random(7);
    static final AtomicBoolean storm = new AtomicBoolean();
    static final AtomicInteger crashes = new AtomicInteger();
    /** How many times each step's side effect happened; at-least-once, so crashes cause repeats. */
    static final Map<String, AtomicInteger> effects = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition fulfilment() {
            return WorkflowDefinition.builder("chaos-fulfilment")
                    .step("reserve", 20, ctx -> doWork(ctx, "reserve"))
                    .step("charge", 20, ctx -> doWork(ctx, "charge"))
                    .step("ship", 20, ctx -> doWork(ctx, "ship"))
                    .build();
        }

        private static Map<String, Object> doWork(com.stepflow.definition.StepContext ctx, String step)
                throws InterruptedException {
            if (storm.get() || (ctx.attempt() <= 2 && roll() < 0.3)) {
                crashes.incrementAndGet();
                throw new WorkerCrash("worker died during " + step);
            }
            Thread.sleep(5);
            effects.computeIfAbsent(ctx.runId() + "/" + step, k -> new AtomicInteger()).incrementAndGet();
            return Map.of("step", step, "attempt", ctx.attempt());
        }

        private static synchronized double roll() {
            return random.nextDouble();
        }
    }

    @Autowired
    private RunService runService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        effects.clear();
        crashes.set(0);
        storm.set(false);
    }

    @AfterEach
    void calm() {
        storm.set(false);
    }

    private List<UUID> startRuns(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(runService.start("chaos-fulfilment", Map.of("n", i)).id());
        }
        return ids;
    }

    private void waitForAll(List<UUID> ids, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline
                && !ids.stream().allMatch(id -> runService.get(id).status().isTerminal())) {
            Thread.sleep(100);
        }
    }

    private void assertNothingLostOrDoubled(List<UUID> ids) {
        for (UUID id : ids) {
            assertEquals(RunStatus.COMPLETED, runService.get(id).status(), "run " + id);
            var steps = runService.steps(id);
            assertEquals(3, steps.size());
            for (var step : steps) {
                assertEquals(StepStatus.SUCCEEDED, step.status(), step.stepName() + " of " + id);
                assertNull(step.leaseOwner(), "no lease may be left behind");
                assertNotNull(step.output(), "each step's result was recorded");
                assertTrue(effects.containsKey(id + "/" + step.stepName()), "the work for " + step.stepName() + " happened");
            }
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM step_executions WHERE status NOT IN ('SUCCEEDED')", Integer.class));
    }

    @Test
    void randomWorkerDeathsNeverLoseOrStallARun() throws Exception {
        List<UUID> ids = startRuns(40);

        waitForAll(ids, 90_000);

        assertTrue(crashes.get() > 10, "the test must actually crash workers, crashed " + crashes.get());
        assertNothingLostOrDoubled(ids);
        // a crash can repeat a side effect (at-least-once) but never more than once per lost attempt
        int repeats = effects.values().stream().mapToInt(c -> c.get() - 1).sum();
        assertTrue(repeats <= crashes.get(), repeats + " repeats from " + crashes.get() + " crashes");
    }

    @Test
    void everyWorkerDyingForAWhileOnlyDelaysTheRuns() throws Exception {
        storm.set(true);
        List<UUID> ids = startRuns(20);

        Thread.sleep(2_000);
        assertTrue(ids.stream().noneMatch(id -> runService.get(id).status() == RunStatus.COMPLETED),
                "nothing can finish while every worker is dying");
        storm.set(false);

        waitForAll(ids, 90_000);
        assertNothingLostOrDoubled(ids);
        assertTrue(crashes.get() >= 20, "crashed " + crashes.get());
    }
}
