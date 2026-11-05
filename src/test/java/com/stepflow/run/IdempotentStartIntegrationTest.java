package com.stepflow.run;

import com.stepflow.definition.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
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
class IdempotentStartIntegrationTest {

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition payout() {
            return WorkflowDefinition.builder("payout")
                    .step("transfer", ctx -> Map.of())
                    .build();
        }

        @Bean
        WorkflowDefinition refund() {
            return WorkflowDefinition.builder("refund")
                    .step("transfer-back", ctx -> Map.of())
                    .build();
        }
    }

    @Autowired
    private RunService runService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneRun() throws Exception {
        int clients = 12;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RunService.StartResult>> results = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return runService.start("payout", Map.of("amount", 1500, "to", "acct-9"), "payout-77");
            }));
        }
        start.countDown();

        Set<UUID> runIds = new HashSet<>();
        int created = 0;
        for (Future<RunService.StartResult> result : results) {
            RunService.StartResult r = result.get(20, TimeUnit.SECONDS);
            runIds.add(r.run().id());
            if (r.created()) {
                created++;
            }
        }
        pool.shutdown();

        assertEquals(1, runIds.size(), "every client must get the same run");
        assertEquals(1, created, "exactly one request creates it");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM workflow_runs", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM step_executions", Integer.class),
                "the first step is queued once");
    }

    @Test
    void inputKeyOrderDoesNotMatter() {
        UUID first = runService.start("payout", Map.of("amount", 10, "to", "a"), "k-order").run().id();
        RunService.StartResult again = runService.start("payout", Map.of("to", "a", "amount", 10), "k-order");

        assertFalse(again.created());
        assertEquals(first, again.run().id());
    }

    @Test
    void sameKeyForAnotherWorkflowIsAConflict() {
        runService.start("payout", Map.of(), "k-type");

        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> runService.start("refund", Map.of(), "k-type"));
        assertNotNull(ex.existingRunId());
    }

    @Test
    void blankKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> runService.start("payout", Map.of(), "  "));
    }
}
