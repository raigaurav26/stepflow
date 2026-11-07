package com.stepflow.worker;

import com.stepflow.definition.RetryPolicy;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepKind;
import com.stepflow.domain.StepStatus;
import com.stepflow.run.RunService;
import com.stepflow.run.StepExecution;
import com.stepflow.run.StepQueue;
import com.stepflow.run.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A worker "crashes" by claiming a step and never reporting back; its lease is then expired by hand. */
@SpringBootTest
class LeaseRecoveryIntegrationTest {

    static final AtomicInteger chargeCalls = new AtomicInteger();
    static final AtomicInteger shipCalls = new AtomicInteger();

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition charge() {
            return WorkflowDefinition.builder("charge")
                    .step("charge-card", 3, ctx -> {
                        chargeCalls.incrementAndGet();
                        return Map.of("attempt", ctx.attempt());
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition lastChance() {
            return WorkflowDefinition.builder("last-chance")
                    .step("reserve", 1, RetryPolicy.fixed(Duration.ZERO), ctx -> Map.of())
                    .compensateWith(ctx -> Map.of("released", true))
                    .step("ship", 1, ctx -> {
                        shipCalls.incrementAndGet();
                        return Map.of();
                    })
                    .build();
        }
    }

    @Autowired
    private RunService runService;

    @Autowired
    private StepQueue queue;

    @Autowired
    private WorkerPool workers;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        chargeCalls.set(0);
        shipCalls.set(0);
    }

    private StepExecution crashWhileHolding(String workerId) {
        StepExecution claimed = queue.claim(workerId, 1, Duration.ofMinutes(5)).get(0);
        jdbc.update("UPDATE step_executions SET lease_expires_at = now() - interval '1 second' WHERE id = ?",
                claimed.id());
        return claimed;
    }

    @Test
    void stepOfACrashedWorkerIsPickedUpByAnotherWorker() {
        UUID id = runService.start("charge", Map.of()).id();
        StepExecution lost = crashWhileHolding("worker-that-died");

        assertEquals(List.of(lost.id()), queue.reclaimExpiredLeases(10));
        StepExecution back = runService.steps(id).get(0);
        assertEquals(StepStatus.PENDING, back.status());
        assertNull(back.leaseOwner());
        assertEquals("Lease held by worker-that-died expired on attempt 1", back.lastError());

        workers.drainOnce(10);

        WorkflowRun run = runService.get(id);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(2, run.output().get("attempt"), "the lost attempt still counts");
        assertEquals(1, chargeCalls.get());
        assertFalse(queue.markSucceeded(lost.id(), "worker-that-died", Map.of("late", true)),
                "the dead worker's late result must be ignored");
    }

    @Test
    void liveLeasesAreLeftAlone() {
        runService.start("charge", Map.of());
        queue.claim("healthy-worker", 1, Duration.ofMinutes(5));

        assertTrue(queue.reclaimExpiredLeases(10).isEmpty());
    }

    @Test
    void crashOnTheLastAttemptFailsTheStepWithoutRunningItAgain() {
        UUID id = runService.start("last-chance", Map.of()).id();
        workers.drainOnce(10);
        crashWhileHolding("worker-that-died");
        queue.reclaimExpiredLeases(10);

        workers.drainOnce(10);
        workers.drainOnce(10);

        WorkflowRun run = runService.get(id);
        assertEquals(RunStatus.COMPENSATED, run.status(), "a lost final attempt rolls the run back like any failure");
        assertTrue(run.error().contains("expired"), run.error());
        assertEquals(0, shipCalls.get(), "ship had one attempt and it was lost with the worker");
        List<StepExecution> steps = runService.steps(id);
        assertTrue(steps.stream().anyMatch(s -> s.stepName().equals("ship") && s.status() == StepStatus.DEAD));
        assertTrue(steps.stream().anyMatch(s -> s.kind() == StepKind.COMPENSATION && s.status() == StepStatus.SUCCEEDED));
    }
}
