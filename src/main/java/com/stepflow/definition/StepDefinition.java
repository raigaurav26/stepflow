package com.stepflow.definition;

import java.util.Objects;

/**
 * @param retryPolicy  how to space out retries, or null to use the application default
 * @param compensation undoes this step's work if a later step fails for good, or null if
 *                     there is nothing to undo
 */
public record StepDefinition(String name, int maxAttempts, RetryPolicy retryPolicy,
                             StepHandler handler, StepHandler compensation) {

    public StepDefinition {
        Objects.requireNonNull(handler, "handler");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step name is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 for step " + name);
        }
    }

    public boolean hasCompensation() {
        return compensation != null;
    }

    StepDefinition withCompensation(StepHandler undo) {
        return new StepDefinition(name, maxAttempts, retryPolicy, handler, undo);
    }

    public RetryPolicy retryPolicyOr(RetryPolicy fallback) {
        return retryPolicy != null ? retryPolicy : fallback;
    }
}
