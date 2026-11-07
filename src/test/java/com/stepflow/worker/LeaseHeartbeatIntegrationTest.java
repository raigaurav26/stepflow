package com.stepflow.worker;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.run.RunService;
import com.stepflow.run.StepQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A step that runs for longer than the lease keeps it by renewing, so it isn't run twice. */
@SpringBootTest(properties = "stepflow.worker.lease-duration=900ms")
class LeaseHeartbeatIntegrationTest {

    static final AtomicInteger calls = new AtomicInteger();

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition slowExport() {
            return WorkflowDefinition.builder("slow-export")
                    .step("export", ctx -> {
                        calls.incrementAndGet();
                        Thread.sleep(2_500);
                        return Map.of("rows", 10_000);
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
        calls.set(0);
    }

    @Test
    void longStepRenewsItsLeaseWhileRunning() throws Exception {
        UUID id = runService.start("slow-export", Map.of()).id();

        CompletableFuture<Integer> worker = CompletableFuture.supplyAsync(() -> workers.drainOnce(1));
        List<Long> reclaimed = new ArrayList<>();
        while (!worker.isDone()) {
            reclaimed.addAll(queue.reclaimExpiredLeases(10));
            Thread.sleep(100);
        }
        worker.get(10, TimeUnit.SECONDS);

        assertTrue(reclaimed.isEmpty(), "a step that is still running must not be reclaimed: " + reclaimed);
        assertEquals(1, calls.get());
        assertEquals(RunStatus.COMPLETED, runService.get(id).status());
    }
}
