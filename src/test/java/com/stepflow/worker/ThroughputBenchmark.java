package com.stepflow.worker;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.run.RunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

/**
 * Opt-in benchmark: {@code mvn test -Dtest=ThroughputBenchmark -Dstepflow.bench=true}.
 * Starts runs of a 3-step no-op workflow while workers are live and measures total time until all finish.
 */
@SpringBootTest(properties = {
        "stepflow.worker.enabled=true",
        "stepflow.worker.poll-interval=5ms"
})
@DirtiesContext
@EnabledIfSystemProperty(named = "stepflow.bench", matches = "true")
class ThroughputBenchmark {

    @TestConfiguration
    static class Workflows {
        @Bean
        WorkflowDefinition benchFlow() {
            return WorkflowDefinition.builder("bench-flow")
                    .step("a", ctx -> Map.of("ok", true))
                    .step("b", ctx -> Map.of("ok", true))
                    .step("c", ctx -> Map.of("ok", true))
                    .build();
        }
    }

    @Autowired
    RunService runService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WorkerProperties props;

    @Test
    void measure() throws Exception {
        int runs = 2000;
        jdbc.execute("TRUNCATE workflow_runs CASCADE");
        List<UUID> ids = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) ids.add(runService.start("bench-flow", Map.of("n", i)).id());
        long startedAt = System.nanoTime();
        while (jdbc.queryForObject(
                "SELECT count(*) FROM workflow_runs WHERE status NOT IN ('COMPLETED','FAILED','COMPENSATED','CANCELLED')",
                Long.class) > 0) {
            Thread.sleep(50);
        }
        long end = System.nanoTime();
        double drain = (end - t0) / 1e9;
        System.out.printf("BENCH threads=%d runs=%d steps=%d submit=%.2fs total=%.2fs runs/s=%.0f steps/s=%.0f%n",
                props.threads(), runs, runs * 3, (startedAt - t0) / 1e9, drain, runs / drain, runs * 3 / drain);
    }
}
