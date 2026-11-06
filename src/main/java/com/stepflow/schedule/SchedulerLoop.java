package com.stepflow.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks for due schedules on a fixed delay. Safe to run on every instance. */
@Component
@ConditionalOnProperty(name = "stepflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerLoop {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

    private final ScheduleService scheduleService;

    public SchedulerLoop(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Scheduled(fixedDelayString = "${stepflow.scheduler.poll-interval:1s}")
    public void tick() {
        try {
            while (scheduleService.fireDue(50) == 50) {
                // a full batch means more may be due; keep going until caught up
            }
        } catch (RuntimeException e) {
            log.error("Firing due schedules failed, will retry on the next tick", e);
        }
    }
}
