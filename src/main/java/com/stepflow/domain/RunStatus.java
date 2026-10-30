package com.stepflow.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a workflow run.
 *
 * <pre>
 * PENDING -> RUNNING -> COMPLETED
 *                    -> COMPENSATING -> COMPENSATED
 *                                    -> FAILED
 *                    -> FAILED
 * PENDING/RUNNING -> CANCELLED
 * </pre>
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPENSATING,
    COMPLETED,
    COMPENSATED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(RunStatus next) {
        return allowedNext().contains(next);
    }

    private Set<RunStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(COMPLETED, COMPENSATING, FAILED, CANCELLED);
            case COMPENSATING -> EnumSet.of(COMPENSATED, FAILED);
            case COMPLETED, COMPENSATED, FAILED, CANCELLED -> EnumSet.noneOf(RunStatus.class);
        };
    }
}
