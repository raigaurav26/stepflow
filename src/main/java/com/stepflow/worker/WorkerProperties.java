package com.stepflow.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled       start the background worker pool with the application
 * @param threads       how many steps this instance runs at the same time
 * @param pollInterval  how long to wait between polls when the queue is empty
 * @param leaseDuration how long a claimed step belongs to this worker before others may take it;
 *                      a running step renews its lease every third of this
 * @param reapInterval  how often to look for leases left behind by crashed workers
 */
@ConfigurationProperties("stepflow.worker")
public record WorkerProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("4") int threads,
                               @DefaultValue("500ms") Duration pollInterval,
                               @DefaultValue("30s") Duration leaseDuration,
                               @DefaultValue("10s") Duration reapInterval) {

    public Duration heartbeatInterval() {
        return leaseDuration.dividedBy(3);
    }
}
