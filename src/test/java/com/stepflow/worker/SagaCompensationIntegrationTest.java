package com.stepflow.worker;

import com.stepflow.definition.NonRetryableException;
import com.stepflow.definition.RetryPolicy;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepKind;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SagaCompensationIntegrationTest {

    static final RetryPolicy FAST = RetryPolicy.fixed(Duration.ZERO);
    static final List<String> undone = new CopyOnWriteArrayList<>();
    static final AtomicInteger flakyUndoCalls = new AtomicInteger();

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition tripBooking() {
            return WorkflowDefinition.builder("trip-booking")
                    .step("book-flight", 3, FAST, ctx -> Map.of("pnr", "FL-" + ctx.input().get("traveller")))
                    .compensateWith(ctx -> {
                        undone.add("cancel " + ctx.outputOf("book-flight").get("pnr"));
                        return Map.of("refunded", true);
                    })
                    .step("book-hotel", 3, FAST, ctx -> Map.of("bookingId", "HT-42"))
                    .compensateWith(ctx -> {
                        undone.add("cancel " + ctx.outputOf("book-hotel").get("bookingId"));
                        return Map.of();
                    })
                    .step("send-itinerary", 3, FAST, ctx -> Map.of("sent", true))
                    .step("charge-card", 3, FAST, ctx -> {
                        throw new NonRetryableException("card declined");
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition failsAtFirstStep() {
            return WorkflowDefinition.builder("fails-at-first-step")
                    .step("validate", 1, FAST, ctx -> {
                        throw new IllegalArgumentException("bad input");
                    })
                    .compensateWith(ctx -> {
                        undone.add("should never run");
                        return Map.of();
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition flakyUndo() {
            return WorkflowDefinition.builder("flaky-undo")
                    .step("reserve", 3, FAST, ctx -> Map.of())
                    .compensateWith(ctx -> {
                        if (flakyUndoCalls.incrementAndGet() == 1) {
                            throw new IllegalStateException("inventory service timed out");
                        }
                        undone.add("release");
                        return Map.of();
                    })
                    .step("pay", 1, FAST, ctx -> {
                        throw new IllegalStateException("payment gateway down");
                    })
                    .build();
        }

        @Bean
        WorkflowDefinition brokenUndo() {
            return WorkflowDefinition.builder("broken-undo")
                    .step("open-account", 1, FAST, ctx -> Map.of())
                    .compensateWith(ctx -> {
                        undone.add("close-account");
                        return Map.of();
                    })
                    .step("issue-card", 2, FAST, ctx -> Map.of())
                    .compensateWith(ctx -> {
                        throw new IllegalStateException("card system offline");
                    })
                    .step("kyc-check", 1, FAST, ctx -> {
                        throw new NonRetryableException("KYC rejected");
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
    void reset() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        undone.clear();
        flakyUndoCalls.set(0);
    }

    private WorkflowRun runToTheEnd(String type, Map<String, Object> input) {
        UUID id = runService.start(type, input).id();
        for (int i = 0; i < 30 && !runService.get(id).status().isTerminal(); i++) {
            workers.drainOnce(10);
        }
        return runService.get(id);
    }

    private static StepExecution find(List<StepExecution> steps, String name, StepKind kind) {
        return steps.stream().filter(s -> s.stepName().equals(name) && s.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " row for " + name));
    }

    @Test
    void laterFailureUndoesEarlierStepsInReverseOrder() {
        WorkflowRun run = runToTheEnd("trip-booking", Map.of("traveller", "gaurav"));

        assertEquals(RunStatus.COMPENSATED, run.status());
        assertTrue(run.error().contains("charge-card") && run.error().contains("card declined"), run.error());
        assertEquals(List.of("cancel HT-42", "cancel FL-gaurav"), undone, "hotel first, then flight");

        List<StepExecution> steps = runService.steps(run.id());
        assertEquals(StepStatus.COMPENSATED, find(steps, "book-flight", StepKind.ACTION).status());
        assertEquals(StepStatus.COMPENSATED, find(steps, "book-hotel", StepKind.ACTION).status());
        assertEquals(StepStatus.SUCCEEDED, find(steps, "send-itinerary", StepKind.ACTION).status(),
                "a step with nothing to undo stays as it was");
        assertEquals(StepStatus.DEAD, find(steps, "charge-card", StepKind.ACTION).status());
        assertEquals(Map.of("refunded", true), find(steps, "book-flight", StepKind.COMPENSATION).output());
        assertEquals(6, steps.size());
    }

    @Test
    void failureOfTheFirstStepHasNothingToUndo() {
        WorkflowRun run = runToTheEnd("fails-at-first-step", Map.of());

        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(undone.isEmpty());
        assertEquals(1, runService.steps(run.id()).size());
    }

    @Test
    void compensationIsRetriedLikeAnyStep() {
        WorkflowRun run = runToTheEnd("flaky-undo", Map.of());

        assertEquals(RunStatus.COMPENSATED, run.status());
        assertEquals(List.of("release"), undone);
        StepExecution undo = find(runService.steps(run.id()), "reserve", StepKind.COMPENSATION);
        assertEquals(2, undo.attempt());
    }

    @Test
    void compensationThatKeepsFailingStopsTheRollbackAndFailsTheRun() {
        WorkflowRun run = runToTheEnd("broken-undo", Map.of());

        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.error().startsWith("Compensation of step 'issue-card' failed"), run.error());
        assertTrue(run.error().contains("KYC rejected"), "the original failure is kept: " + run.error());
        assertTrue(undone.isEmpty(), "steps below a failed undo are left for a human to look at");

        List<StepExecution> steps = runService.steps(run.id());
        assertEquals(StepStatus.DEAD, find(steps, "issue-card", StepKind.COMPENSATION).status());
        assertEquals(StepStatus.SUCCEEDED, find(steps, "issue-card", StepKind.ACTION).status());
        assertEquals(StepStatus.SUCCEEDED, find(steps, "open-account", StepKind.ACTION).status());
    }
}
