package com.stepflow.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled       start the background worker pool with the application
 * @param threads       how many steps this instance runs at the same time
 * @param pollInterval  how long to wait between polls when the queue is empty
 * @param leaseDuration how long a claimed step belongs to this worker before others may take it
 */
@ConfigurationProperties("stepflow.worker")
public record WorkerProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("4") int threads,
                               @DefaultValue("500ms") Duration pollInterval,
                               @DefaultValue("30s") Duration leaseDuration) {
}
