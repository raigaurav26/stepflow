package com.stepflow.worker;

import com.stepflow.definition.NonRetryableException;
import com.stepflow.definition.RetryPolicy;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepStatus;
import com.stepflow.run.RunService;
import com.stepflow.run.StepExecution;
import com.stepflow.run.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StepExecutorIntegrationTest {

    static final AtomicInteger flakyCalls = new AtomicInteger();
    static final RetryPolicy FAST = RetryPolicy.fixed(Duration.ZERO);

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition pricing() {
            return WorkflowDefinition.builder("pricing")
                    .step("base-price", ctx -> Map.of("price", ((Number) ctx.input().get("qty")).intValue() * 100))
                    .step("discount", ctx -> {
                        int price = ((Number) ctx.outputOf("base-price").get("price")).intValue();
                        return Map.of("price", price - 50);
                    })
                    .step("tax", ctx -> {
                        int price = ((Number) ctx.outputOf("discount").get("price")).intValue();
                        return Map.of("total", price + price / 10);
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition flaky() {
            return WorkflowDefinition.builder("flaky")
                    .step("call-partner", 3, FAST, ctx -> {
                        if (flakyCalls.incrementAndGet() < 3) {
                            throw new IllegalStateException("partner timed out");
                        }
                        return Map.of("attempt", ctx.attempt());
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition broken() {
            return WorkflowDefinition.builder("broken")
                    .step("always-fails", 2, FAST, ctx -> {
                        throw new IllegalArgumentException("card declined");
                    })
                    .step("never-reached", ctx -> Map.of())
                    .build();
        }

        @Bean
        WorkflowDefinition slowRetry() {
            return WorkflowDefinition.builder("slow-retry")
                    .step("call-partner", 5, new RetryPolicy(Duration.ofMinutes(10), 2.0, Duration.ofHours(1), 0.0),
                            ctx -> {
                                throw new IllegalStateException("503 from partner");
                            })
                    .build();
        }

        @Bean
        WorkflowDefinition declined() {
            return WorkflowDefinition.builder("declined")
                    .step("charge-card", 5, FAST, ctx -> {
                        flakyCalls.incrementAndGet();
                        throw new NonRetryableException("card declined by issuer");
                    })
                    .build();
        }
    }

    @Autowired
    private RunService runService;

    @Autowired
    private WorkerPool workers;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        flakyCalls.set(0);
    }

    private WorkflowRun runToTheEnd(String type, Map<String, Object> input) {
        UUID id = runService.start(type, input).id();
        for (int i = 0; i < 20 && !runService.get(id).status().isTerminal(); i++) {
            workers.drainOnce(10);
        }
        return runService.get(id);
    }

    @Test
    void stepsRunInOrderAndSeeEarlierOutputs() {
        WorkflowRun run = runToTheEnd("pricing", Map.of("qty", 3));

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(275, run.output().get("total"));
        assertNotNull(run.completedAt());

        List<StepExecution> steps = runService.steps(run.id());
        assertEquals(List.of("base-price", "discount", "tax"), steps.stream().map(StepExecution::stepName).toList());
        assertTrue(steps.stream().allMatch(s -> s.status() == StepStatus.SUCCEEDED && s.attempt() == 1));
        assertTrue(steps.stream().allMatch(s -> s.leaseOwner() == null));
    }

    @Test
    void failedAttemptsAreRetriedUntilTheStepSucceeds() {
        WorkflowRun run = runToTheEnd("flaky", Map.of());

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(3, run.output().get("attempt"));
        StepExecution step = runService.steps(run.id()).get(0);
        assertEquals(3, step.attempt());
        assertNull(step.lastError());
    }

    @Test
    void stepThatRunsOutOfAttemptsFailsTheRun() {
        WorkflowRun run = runToTheEnd("broken", Map.of());

        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.error().contains("card declined"), run.error());

        List<StepExecution> steps = runService.steps(run.id());
        assertEquals(1, steps.size(), "later steps are never queued");
        assertEquals(StepStatus.DEAD, steps.get(0).status());
        assertEquals(2, steps.get(0).attempt());
        assertEquals("IllegalArgumentException: card declined", steps.get(0).lastError());
    }

    @Test
    void failedStepWaitsForItsBackoffBeforeRunningAgain() {
        UUID id = runService.start("slow-retry", Map.of()).id();

        workers.drainOnce(10);
        StepExecution afterFirst = runService.steps(id).get(0);
        assertEquals(StepStatus.RETRY_SCHEDULED, afterFirst.status());
        Duration wait = Duration.between(Instant.now(), afterFirst.nextRunAt());
        assertTrue(wait.compareTo(Duration.ofMinutes(9)) > 0 && wait.compareTo(Duration.ofMinutes(11)) < 0,
                "first retry should be about 10 minutes away, was " + wait);

        assertEquals(0, workers.drainOnce(10), "the step must not run again before its retry time");

        // pretend the 10 minutes have passed: the second failure should wait twice as long
        jdbc.update("UPDATE step_executions SET next_run_at = now() WHERE run_id = ?", id);
        workers.drainOnce(10);
        StepExecution afterSecond = runService.steps(id).get(0);
        assertEquals(2, afterSecond.attempt());
        Duration secondWait = Duration.between(Instant.now(), afterSecond.nextRunAt());
        assertTrue(secondWait.compareTo(Duration.ofMinutes(19)) > 0, "second retry should be about 20 minutes away");
        assertEquals("IllegalStateException: 503 from partner", afterSecond.lastError());
    }

    @Test
    void nonRetryableErrorSkipsTheRemainingAttempts() {
        WorkflowRun run = runToTheEnd("declined", Map.of());

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals(1, flakyCalls.get(), "the handler must run only once");
        StepExecution step = runService.steps(run.id()).get(0);
        assertEquals(StepStatus.DEAD, step.status());
        assertEquals(1, step.attempt());
    }

    @Test
    void stepOfAWorkflowThatNoLongerExistsIsDead() {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_runs (id, workflow_type, status) VALUES (?, 'removed', 'PENDING')", runId);
        jdbc.update("INSERT INTO step_executions (run_id, step_index, step_name, status) VALUES (?, 0, 'x', 'PENDING')",
                runId);

        workers.drainOnce(10);

        assertEquals(RunStatus.FAILED, runService.get(runId).status());
        assertEquals(StepStatus.DEAD, runService.steps(runId).get(0).status());
    }
}
