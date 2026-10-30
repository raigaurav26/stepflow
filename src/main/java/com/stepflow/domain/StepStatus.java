package com.stepflow.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a single step execution.
 *
 * <p>A step is picked up by a worker (LEASED), runs, and either succeeds or fails.
 * A failed attempt goes back to RETRY_SCHEDULED until it runs out of attempts, then
 * it is DEAD. A succeeded step can later be COMPENSATED when a later step in the
 * same run gives up.
 */
public enum StepStatus {
    PENDING,
    LEASED,
    SUCCEEDED,
    RETRY_SCHEDULED,
    DEAD,
    COMPENSATED;

    public boolean isTerminal() {
        return this == DEAD || this == COMPENSATED;
    }

    public boolean canTransitionTo(StepStatus next) {
        return allowedNext().contains(next);
    }

    private Set<StepStatus> allowedNext() {
        return switch (this) {
            case PENDING, RETRY_SCHEDULED -> EnumSet.of(LEASED);
            // a lease that expires (crashed worker) sends the step back to PENDING
            case LEASED -> EnumSet.of(SUCCEEDED, RETRY_SCHEDULED, DEAD, PENDING);
            case SUCCEEDED -> EnumSet.of(COMPENSATED);
            case DEAD, COMPENSATED -> EnumSet.noneOf(StepStatus.class);
        };
    }
}
