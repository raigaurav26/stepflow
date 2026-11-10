package com.stepflow.api;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.run.RunService;
import com.stepflow.worker.WorkerPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardIntegrationTest {

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition dashQuick() {
            return WorkflowDefinition.builder("dash-quick").step("only", ctx -> Map.of()).build();
        }

        @Bean
        WorkflowDefinition dashSleepy() {
            return WorkflowDefinition.builder("dash-sleepy").sleep("nap", Duration.ofHours(1)).build();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunService runService;

    @Autowired
    private WorkerPool workers;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE workflow_runs, schedules CASCADE");
    }

    @Test
    void dashboardPageIsServedAtTheRoot() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>StepFlow</title>")))
                .andExpect(content().string(containsString("/api/overview")));
    }

    @Test
    void emptySystemReportsZeroForEveryStatus() throws Exception {
        mvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.PENDING").value(0))
                .andExpect(jsonPath("$.runs.FAILED").value(0))
                .andExpect(jsonPath("$.finishedLastHour.COMPLETED").value(0))
                .andExpect(jsonPath("$.runnableSteps").value(0))
                .andExpect(jsonPath("$.oldestRunnableSecs").value(0.0))
                .andExpect(jsonPath("$.schedules.enabled").value(0));
    }

    @Test
    void overviewCountsRunsAndQueueState() throws Exception {
        runService.start("dash-quick", Map.of());
        runService.start("dash-quick", Map.of());
        workers.drainOnce(10);                          // both finish
        runService.start("dash-quick", Map.of());       // still waiting for a worker
        runService.start("dash-sleepy", Map.of());      // asleep for an hour

        mvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.COMPLETED").value(2))
                .andExpect(jsonPath("$.runs.PENDING").value(2))
                .andExpect(jsonPath("$.finishedLastHour.COMPLETED").value(2))
                .andExpect(jsonPath("$.runnableSteps").value(1))
                .andExpect(jsonPath("$.waitingSteps").value(1))
                .andExpect(jsonPath("$.leasedSteps").value(0));
    }

    @Test
    void runListCanBeFilteredByStatus() throws Exception {
        runService.start("dash-quick", Map.of());
        workers.drainOnce(10);
        runService.start("dash-sleepy", Map.of());

        mvc.perform(get("/api/runs").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[*].status", everyItem(is("COMPLETED"))));
        mvc.perform(get("/api/runs"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/runs").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }
}
