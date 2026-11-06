package com.stepflow.schedule;

import com.stepflow.definition.WorkflowRegistry;
import com.stepflow.run.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleRepository schedules;
    private final RunService runService;
    private final WorkflowRegistry registry;
    private final Clock clock;

    @Autowired
    public ScheduleService(ScheduleRepository schedules, RunService runService, WorkflowRegistry registry) {
        this(schedules, runService, registry, Clock.systemUTC());
    }

    ScheduleService(ScheduleRepository schedules, RunService runService, WorkflowRegistry registry, Clock clock) {
        this.schedules = schedules;
        this.runService = runService;
        this.registry = registry;
        this.clock = clock;
    }

    @Transactional
    public Schedule create(String name, String workflowType, String cron, String zone, Map<String, Object> input) {
        registry.get(workflowType);
        String safeZone = zone == null || zone.isBlank() ? "UTC" : zone;
        CronSchedule schedule = CronSchedule.parse(cron, safeZone);
        UUID id = UUID.randomUUID();
        try {
            schedules.insert(id, name, workflowType, cron, safeZone, input == null ? Map.of() : input,
                    schedule.nextAfter(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new ScheduleNameTakenException(name);
        }
        return get(id);
    }

    public Schedule get(UUID id) {
        return schedules.find(id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    public List<Schedule> list() {
        return schedules.findAll();
    }

    @Transactional
    public Schedule setEnabled(UUID id, boolean enabled) {
        Schedule existing = get(id);
        // when switching back on, don't fire for every slot missed while it was off
        Instant next = enabled && !existing.enabled()
                ? CronSchedule.parse(existing.cron(), existing.zone()).nextAfter(clock.instant())
                : null;
        schedules.setEnabled(id, enabled, next);
        return get(id);
    }

    public void delete(UUID id) {
        if (!schedules.delete(id)) {
            throw new ScheduleNotFoundException(id);
        }
    }

    /**
     * Starts a run for every schedule that is due and moves it to its next fire time, all in
     * one transaction. The run's idempotency key is the schedule id plus the fire time, so
     * even if this transaction is somehow repeated, a slot can never start two runs.
     *
     * <p>After downtime a schedule fires once for the slot it missed, then jumps to the next
     * slot after now instead of replaying every missed slot.
     *
     * @return how many runs were started
     */
    @Transactional
    public int fireDue(int limit) {
        List<Schedule> due = schedules.lockDue(limit);
        Instant now = clock.instant();
        for (Schedule schedule : due) {
            if (!registry.contains(schedule.workflowType())) {
                log.warn("Schedule {} points at unknown workflow {}, disabling it", schedule.name(), schedule.workflowType());
                schedules.setEnabled(schedule.id(), false, null);
                continue;
            }
            String key = "schedule:" + schedule.id() + ":" + schedule.nextFireAt().getEpochSecond();
            UUID runId = runService.start(schedule.workflowType(), schedule.input(), key).run().id();

            Instant from = schedule.nextFireAt().isAfter(now) ? schedule.nextFireAt() : now;
            Instant next = CronSchedule.parse(schedule.cron(), schedule.zone()).nextAfter(from);
            schedules.recordFired(schedule.id(), schedule.nextFireAt(), runId, next);
            log.info("Schedule {} started run {}; next at {}", schedule.name(), runId, next);
        }
        return due.size();
    }
}
