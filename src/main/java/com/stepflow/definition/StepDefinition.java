package com.stepflow.definition;

import java.util.Objects;

/**
 * @param retryPolicy how to space out retries, or null to use the application default
 */
public record StepDefinition(String name, int maxAttempts, RetryPolicy retryPolicy, StepHandler handler) {

    public StepDefinition {
        Objects.requireNonNull(handler, "handler");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step name is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 for step " + name);
        }
    }

    public RetryPolicy retryPolicyOr(RetryPolicy fallback) {
        return retryPolicy != null ? retryPolicy : fallback;
    }
}
