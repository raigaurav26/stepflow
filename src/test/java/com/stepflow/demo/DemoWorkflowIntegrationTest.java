package com.stepflow.demo;

import com.stepflow.domain.RunStatus;
import com.stepflow.domain.StepKind;
import com.stepflow.domain.StepStatus;
import com.stepflow.run.RunService;
import com.stepflow.run.StepExecution;
import com.stepflow.run.WorkflowRun;
import com.stepflow.worker.WorkerPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "stepflow.demo.enabled=true",
        "stepflow.demo.cooling-off=1h",
        "stepflow.demo.retry-delay=0s",
        "stepflow.demo.latency=0s"
})
class DemoWorkflowIntegrationTest {

    @Autowired
    private RunService runService;

    @Autowired
    private WorkerPool workers;

    @Autowired
    private DemoLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        ledger.clear();
    }

    /** Runs the workflow to the end, skipping the real wait of the cooling-off sleep step. */
    private WorkflowRun finish(Map<String, Object> input) {
        UUID id = runService.start("order-fulfilment", input).id();
        for (int i = 0; i < 40 && !runService.get(id).status().isTerminal(); i++) {
            jdbc.update("UPDATE step_executions SET next_run_at = now() WHERE run_id = ? AND status IN ('PENDING', 'RETRY_SCHEDULED')", id);
            workers.drainOnce(10);
        }
        return runService.get(id);
    }

    @Test
    void happyPathReservesChargesWaitsAndShips() {
        WorkflowRun run = finish(Map.of("orderId", "A-1", "amount", 499));

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals("TRK-A-1", run.output().get("tracking"));
        assertEquals(List.of("reserved stock for A-1", "charged 499 for A-1", "shipped A-1"), ledger.entries());
        assertEquals(List.of("reserve-stock", "charge-card", "cooling-off", "ship"),
                runService.steps(run.id()).stream().map(StepExecution::stepName).toList());
    }

    @Test
    void cooldownIsADurableTimerNotAThreadSleeping() {
        UUID id = runService.start("order-fulfilment", Map.of("orderId", "A-9", "amount", 5)).id();
        workers.drainOnce(10);   // reserve-stock
        workers.drainOnce(10);   // charge-card

        List<StepExecution> steps = runService.steps(id);
        StepExecution cooling = steps.get(2);
        assertEquals("cooling-off", cooling.stepName());
        assertEquals(StepStatus.PENDING, cooling.status());
        assertTrue(cooling.nextRunAt().isAfter(java.time.Instant.now().plusSeconds(3000)), "about an hour away");
        assertEquals(0, workers.drainOnce(10));
        assertEquals(RunStatus.RUNNING, runService.get(id).status());
    }

    @Test
    void flakyPaymentIsRetriedThenSucceeds() {
        WorkflowRun run = finish(Map.of("orderId", "A-2", "amount", 10, "payment", "flaky"));

        assertEquals(RunStatus.COMPLETED, run.status());
        StepExecution charge = runService.steps(run.id()).stream()
                .filter(s -> s.stepName().equals("charge-card")).findFirst().orElseThrow();
        assertEquals(3, charge.attempt());
        assertEquals(1, ledger.entries().stream().filter(e -> e.startsWith("charged")).count(),
                "the card is charged once however many attempts it took");
    }

    @Test
    void declinedCardReleasesTheStockAndStops() {
        WorkflowRun run = finish(Map.of("orderId", "A-3", "amount", 10, "payment", "declined"));

        assertEquals(RunStatus.COMPENSATED, run.status());
        assertTrue(run.error().contains("card declined by issuer"), run.error());
        assertEquals(List.of("reserved stock for A-3", "released stock for A-3"), ledger.entries());
        assertTrue(runService.steps(run.id()).stream().noneMatch(s -> s.stepName().equals("ship")));
    }

    @Test
    void shippingFailureRefundsAndReleasesNewestFirst() {
        WorkflowRun run = finish(Map.of("orderId", "A-4", "amount", 250, "shipping", "fail"));

        assertEquals(RunStatus.COMPENSATED, run.status());
        assertEquals(List.of("reserved stock for A-4", "charged 250 for A-4",
                "refunded 250 for A-4", "released stock for A-4"), ledger.entries());
        List<StepExecution> undo = runService.steps(run.id()).stream()
                .filter(s -> s.kind() == StepKind.COMPENSATION).toList();
        assertEquals(List.of("charge-card", "reserve-stock"), undo.stream().map(StepExecution::stepName).toList());
    }
}
