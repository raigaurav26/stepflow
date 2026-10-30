package com.stepflow.domain;

public final class Transitions {

    private Transitions() {
    }

    public static RunStatus check(RunStatus from, RunStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalTransitionException(from, to);
        }
        return to;
    }

    public static StepStatus check(StepStatus from, StepStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalTransitionException(from, to);
        }
        return to;
    }
}
