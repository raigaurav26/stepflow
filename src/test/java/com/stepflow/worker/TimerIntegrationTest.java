package com.stepflow.worker;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepStatus;
import com.stepflow.run.RunService;
import com.stepflow.run.StepExecution;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TimerIntegrationTest {

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition coolingOff() {
            return WorkflowDefinition.builder("cooling-off")
                    .step("open-loan", ctx -> Map.of("loanId", "L-1"))
                    .sleep("cooling-off-period", Duration.ofHours(2))
                    .step("disburse", ctx -> Map.of("disbursed", ctx.outputOf("open-loan").get("loanId")))
                    .build();
        }

        @Bean
        WorkflowDefinition reminder() {
            return WorkflowDefinition.builder("reminder")
                    .step("send", ctx -> Map.of("sent", true))
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
    }

    private void timePasses(UUID runId) {
        jdbc.update("UPDATE step_executions SET next_run_at = now() WHERE run_id = ? AND status = 'PENDING'", runId);
    }

    private static Duration untilNow(Instant instant) {
        return Duration.between(Instant.now(), instant);
    }

    @Test
    void sleepStepHoldsTheRunWithoutHoldingAThread() {
        UUID id = runService.start("cooling-off", Map.of()).id();

        assertEquals(1, workers.drainOnce(10), "open-loan runs straight away");
        List<StepExecution> steps = runService.steps(id);
        StepExecution sleeping = steps.get(1);
        assertEquals("cooling-off-period", sleeping.stepName());
        assertEquals(StepStatus.PENDING, sleeping.status());
        Duration wait = untilNow(sleeping.nextRunAt());
        assertTrue(wait.compareTo(Duration.ofMinutes(119)) > 0 && wait.compareTo(Duration.ofMinutes(121)) < 0,
                "should wake in about 2 hours, was " + wait);

        assertEquals(0, workers.drainOnce(10), "nothing runs while the timer is pending");
        assertEquals(RunStatus.RUNNING, runService.get(id).status());

        timePasses(id);
        workers.drainOnce(10);
        workers.drainOnce(10);

        assertEquals(RunStatus.COMPLETED, runService.get(id).status());
        assertEquals("L-1", runService.get(id).output().get("disbursed"));
    }

    @Test
    void runCanBeStartedAtALaterTime() {
        Instant at = Instant.now().plus(Duration.ofHours(1));
        UUID id = runService.start("reminder", Map.of(), null, at).run().id();

        assertEquals(RunStatus.PENDING, runService.get(id).status());
        Duration wait = untilNow(runService.steps(id).get(0).nextRunAt());
        assertTrue(wait.compareTo(Duration.ofMinutes(59)) > 0, "first step waits for startAt, was " + wait);
        assertEquals(0, workers.drainOnce(10));

        timePasses(id);
        workers.drainOnce(10);
        assertEquals(RunStatus.COMPLETED, runService.get(id).status());
    }

    @Test
    void startTimeInThePastMeansNow() {
        UUID id = runService.start("reminder", Map.of(), null, Instant.parse("2020-01-01T00:00:00Z")).run().id();

        assertEquals(1, workers.drainOnce(10));
        assertEquals(RunStatus.COMPLETED, runService.get(id).status());
    }
}
