package com.stepflow.definition;

import java.util.Objects;

public record StepDefinition(String name, int maxAttempts, StepHandler handler) {

    public StepDefinition {
        Objects.requireNonNull(handler, "handler");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step name is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 for step " + name);
        }
    }
}
