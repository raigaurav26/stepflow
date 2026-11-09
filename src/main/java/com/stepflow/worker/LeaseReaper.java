package com.stepflow.worker;

import com.stepflow.metrics.StepMetrics;
import com.stepflow.run.StepQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Finds steps whose worker died mid-step and puts them back in the queue. Runs on every
 * worker instance; SKIP LOCKED keeps them from fighting over the same rows.
 */
@Component
@ConditionalOnProperty(name = "stepflow.worker.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final StepQueue queue;
    private final StepMetrics metrics;

    public LeaseReaper(StepQueue queue, StepMetrics metrics) {
        this.queue = queue;
        this.metrics = metrics;
    }

    // DurationStyle accepts the same "10s" style as the rest of the config; plain @Scheduled only takes ms or ISO
    @Scheduled(fixedDelayString = "#{T(org.springframework.boot.convert.DurationStyle)"
            + ".detectAndParse('${stepflow.worker.reap-interval:10s}').toMillis()}")
    public void reap() {
        try {
            List<Long> reclaimed = queue.reclaimExpiredLeases(100);
            metrics.leasesReclaimed(reclaimed.size());
            if (!reclaimed.isEmpty()) {
                log.warn("Put {} step(s) with expired leases back in the queue: {}", reclaimed.size(), reclaimed);
            }
        } catch (RuntimeException e) {
            log.error("Reclaiming expired leases failed, will retry", e);
        }
    }
}
