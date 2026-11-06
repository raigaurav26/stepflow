package com.stepflow.definition;

import java.time.Duration;
import java.util.Objects;

/**
 * @param retryPolicy  how to space out retries, or null to use the application default
 * @param compensation undoes this step's work if a later step fails for good, or null if
 *                     there is nothing to undo
 * @param delay        how long after the previous step (or the run's start) this step may
 *                     begin, or null to begin straight away
 */
public record StepDefinition(String name, int maxAttempts, RetryPolicy retryPolicy,
                             StepHandler handler, StepHandler compensation, Duration delay) {

    public StepDefinition {
        Objects.requireNonNull(handler, "handler");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step name is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 for step " + name);
        }
        if (delay != null && delay.isNegative()) {
            throw new IllegalArgumentException("delay cannot be negative for step " + name);
        }
    }

    public boolean hasCompensation() {
        return compensation != null;
    }

    StepDefinition withCompensation(StepHandler undo) {
        return new StepDefinition(name, maxAttempts, retryPolicy, handler, undo, delay);
    }

    public Duration delayOrZero() {
        return delay == null ? Duration.ZERO : delay;
    }

    public RetryPolicy retryPolicyOr(RetryPolicy fallback) {
        return retryPolicy != null ? retryPolicy : fallback;
    }
}
