package com.stepflow.schedule;

import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.run.RunService;
import com.stepflow.run.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ScheduleIntegrationTest {

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition dailyReport() {
            return WorkflowDefinition.builder("daily-report")
                    .step("build", ctx -> Map.of())
                    .build();
        }
    }

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private RunService runService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE workflow_runs, schedules CASCADE");
    }

    private void makeDue(UUID scheduleId, String ago) {
        jdbc.update("UPDATE schedules SET next_fire_at = now() - ?::interval WHERE id = ?", ago, scheduleId);
    }

    private int runCount() {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_runs", Integer.class);
    }

    @Test
    void newScheduleWaitsForItsFirstSlot() {
        Schedule schedule = scheduleService.create("report", "daily-report", "0 0 9 * * *", "Asia/Kolkata", Map.of());

        assertTrue(schedule.nextFireAt().isAfter(Instant.now()));
        assertEquals(0, scheduleService.fireDue(10));
        assertEquals(0, runCount());
    }

    @Test
    void dueScheduleStartsARunAndMovesToTheNextSlot() {
        Schedule schedule = scheduleService.create("report", "daily-report", "0 0 9 * * *", "UTC",
                Map.of("format", "pdf"));
        makeDue(schedule.id(), "1 second");

        assertEquals(1, scheduleService.fireDue(10));

        Schedule after = scheduleService.get(schedule.id());
        assertNotNull(after.lastRunId());
        assertTrue(after.nextFireAt().isAfter(Instant.now()));
        WorkflowRun run = runService.get(after.lastRunId());
        assertEquals("daily-report", run.workflowType());
        assertEquals("pdf", run.input().get("format"));

        assertEquals(0, scheduleService.fireDue(10), "nothing is due any more");
        assertEquals(1, runCount());
    }

    @Test
    void afterDowntimeItFiresOnceInsteadOfReplayingEverySlot() {
        Schedule schedule = scheduleService.create("every-minute", "daily-report", "0 * * * * *", "UTC", Map.of());
        makeDue(schedule.id(), "3 days");

        scheduleService.fireDue(10);
        scheduleService.fireDue(10);

        assertEquals(1, runCount(), "3 days of missed minutes must not start 4320 runs");
        assertTrue(scheduleService.get(schedule.id()).nextFireAt().isAfter(Instant.now()));
    }

    @Test
    void severalSchedulerInstancesFireADueSlotOnce() throws Exception {
        Schedule schedule = scheduleService.create("report", "daily-report", "0 0 9 * * *", "UTC", Map.of());
        makeDue(schedule.id(), "1 second");

        int instances = 6;
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return scheduleService.fireDue(10);
            }));
        }
        start.countDown();
        int fired = 0;
        for (Future<Integer> result : results) {
            fired += result.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, fired);
        assertEquals(1, runCount());
    }

    @Test
    void pausedScheduleDoesNotFireAndResumingSkipsMissedSlots() {
        Schedule schedule = scheduleService.create("report", "daily-report", "0 * * * * *", "UTC", Map.of());
        scheduleService.setEnabled(schedule.id(), false);
        makeDue(schedule.id(), "1 hour");

        assertEquals(0, scheduleService.fireDue(10));

        Schedule resumed = scheduleService.setEnabled(schedule.id(), true);
        assertTrue(resumed.nextFireAt().isAfter(Instant.now()));
        assertEquals(0, scheduleService.fireDue(10));
    }

    @Test
    void scheduleForAWorkflowThatWasRemovedIsDisabled() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, workflow_type, cron, next_fire_at)
                VALUES (?, 'orphan', 'removed-workflow', '0 * * * * *', now() - interval '1 minute')
                """, id);

        scheduleService.fireDue(10);

        assertFalse(scheduleService.get(id).enabled());
        assertEquals(0, runCount());
    }

    @Test
    void apiValidatesSchedules() throws Exception {
        String body = """
                {"name": "nightly", "workflowType": "daily-report", "cron": "0 30 1 * * *", "zone": "Asia/Kolkata"}
                """;
        mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "bad", "workflowType": "daily-report", "cron": "whenever"}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "ghost", "workflowType": "no-such-workflow", "cron": "0 0 * * * *"}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }
}
