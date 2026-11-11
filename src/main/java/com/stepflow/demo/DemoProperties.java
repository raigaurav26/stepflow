package com.stepflow.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled      register the demo {@code order-fulfilment} workflow
 * @param coolingOff   how long an order waits before it ships (a durable sleep step); zero skips it
 * @param retryDelay   wait between payment retries
 * @param latency      pretend each call to another system takes this long
 */
@ConfigurationProperties("stepflow.demo")
public record DemoProperties(@DefaultValue("false") boolean enabled,
                             @DefaultValue("10s") Duration coolingOff,
                             @DefaultValue("1s") Duration retryDelay,
                             @DefaultValue("300ms") Duration latency) {
}
