package com.stepflow.run;

import java.util.UUID;

/** The idempotency key was already used to start a run with a different workflow or input. */
public class IdempotencyConflictException extends RuntimeException {

    private final UUID existingRunId;

    public IdempotencyConflictException(String key, UUID existingRunId) {
        super("Idempotency key '" + key + "' was already used for a different request");
        this.existingRunId = existingRunId;
    }

    public UUID existingRunId() {
        return existingRunId;
    }
}
