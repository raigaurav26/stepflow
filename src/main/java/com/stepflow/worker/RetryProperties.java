package com.stepflow.worker;

import com.stepflow.definition.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** The retry policy for steps that don't set their own. */
@ConfigurationProperties("stepflow.retry")
public record RetryProperties(@DefaultValue("1s") Duration initialDelay,
                              @DefaultValue("2.0") double multiplier,
                              @DefaultValue("5m") Duration maxDelay,
                              @DefaultValue("0.2") double jitter) {

    public RetryPolicy toPolicy() {
        return new RetryPolicy(initialDelay, multiplier, maxDelay, jitter);
    }
}
