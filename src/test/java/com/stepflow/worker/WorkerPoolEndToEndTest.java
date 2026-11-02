package com.stepflow.worker;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.domain.RunStatus;
import com.stepflow.run.RunService;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Starts the real background pool and checks every step of every run executes exactly once. */
@SpringBootTest(properties = {
        "stepflow.worker.enabled=true",
        "stepflow.worker.threads=6",
        "stepflow.worker.poll-interval=50ms"
})
@DirtiesContext
class WorkerPoolEndToEndTest {

    static final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition signup() {
            return WorkflowDefinition.builder("signup")
                    .step("create-account", ctx -> count(ctx.runId(), "create-account"))
                    .step("send-welcome-email", ctx -> count(ctx.runId(), "send-welcome-email"))
                    .step("add-to-crm", ctx -> count(ctx.runId(), "add-to-crm"))
                    .build();
        }

        private static Map<String, Object> count(UUID runId, String step) throws InterruptedException {
            executions.computeIfAbsent(runId + "/" + step, k -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(5);
            return Map.of("done", step);
        }
    }

    @Autowired
    private RunService runService;

    @BeforeAll
    static void cleanTables(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
    }

    @Test
    void backgroundWorkersFinishEveryRunExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(runService.start("signup", Map.of("user", "u" + i)).id());
        }

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && !ids.stream().allMatch(id -> runService.get(id).status() == RunStatus.COMPLETED)) {
            Thread.sleep(100);
        }

        for (UUID id : ids) {
            assertEquals(RunStatus.COMPLETED, runService.get(id).status(), "run " + id);
        }
        assertEquals(40 * 3, executions.size());
        assertTrue(executions.values().stream().allMatch(c -> c.get() == 1), executions.toString());
    }
}
