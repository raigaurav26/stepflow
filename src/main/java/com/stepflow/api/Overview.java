package com.stepflow.api;

import java.util.Map;

/**
 * Everything the dashboard shows at the top of the page.
 *
 * @param runs               every run by status; statuses with no runs are present with 0
 * @param finishedLastHour   runs that ended in the last hour, by final status
 * @param runnableSteps      steps ready to run now
 * @param waitingSteps       steps scheduled for later (retry backoff, sleeps, delayed starts)
 * @param leasedSteps        steps being run by a worker right now
 * @param oldestRunnableSecs how long the longest-waiting runnable step has waited
 * @param schedules          enabled and paused cron schedules
 */
public record Overview(Map<String, Long> runs,
                       Map<String, Long> finishedLastHour,
                       long runnableSteps,
                       long waitingSteps,
                       long leasedSteps,
                       double oldestRunnableSecs,
                       Map<String, Long> schedules) {
}
