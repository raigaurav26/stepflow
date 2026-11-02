package com.stepflow.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StepQueueIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private StepQueue queue;

    @Autowired
    private RunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
    }

    private UUID newRunWithStep() {
        UUID runId = UUID.randomUUID();
        runs.insertRun(runId, "demo", Map.of(), null);
        runs.insertStep(runId, 0, "only", 3);
        return runId;
    }

    @Test
    void claimLeasesTheStepAndCountsTheAttempt() {
        UUID runId = newRunWithStep();

        List<StepExecution> claimed = queue.claim("worker-a", 10, LEASE);

        assertEquals(1, claimed.size());
        StepExecution step = claimed.get(0);
        assertEquals(runId, step.runId());
        assertEquals("worker-a", step.leaseOwner());
        assertEquals(1, step.attempt());
        assertNotNull(step.leaseExpiresAt());
        assertTrue(queue.claim("worker-b", 10, LEASE).isEmpty(), "a leased step must not be claimed again");
    }

    @Test
    void stepsScheduledForLaterAreNotClaimed() {
        newRunWithStep();
        jdbc.update("UPDATE step_executions SET next_run_at = now() + interval '1 hour'");

        assertTrue(queue.claim("worker-a", 10, LEASE).isEmpty());
    }

    @Test
    void onlyTheLeaseOwnerCanRecordAResult() {
        newRunWithStep();
        StepExecution step = queue.claim("worker-a", 1, LEASE).get(0);

        assertFalse(queue.markSucceeded(step.id(), "worker-b", Map.of("x", 1)));
        assertTrue(queue.markSucceeded(step.id(), "worker-a", Map.of("x", 1)));
        assertFalse(queue.markSucceeded(step.id(), "worker-a", Map.of("x", 2)), "result is written once");
    }

    @Test
    void concurrentWorkersNeverClaimTheSameStep() throws Exception {
        int stepCount = 300;
        for (int i = 0; i < stepCount; i++) {
            newRunWithStep();
        }

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> allClaimed = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            String workerId = "worker-" + w;
            futures.add(pool.submit(() -> {
                start.await();
                List<StepExecution> batch;
                do {
                    batch = queue.claim(workerId, 7, LEASE);
                    batch.forEach(step -> allClaimed.add(step.id()));
                } while (!batch.isEmpty());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Set<Long> unique = new HashSet<>(allClaimed);
        assertEquals(allClaimed.size(), unique.size(), "a step was claimed by two workers");
        assertEquals(stepCount, unique.size(), "every step should be claimed");
    }
}
