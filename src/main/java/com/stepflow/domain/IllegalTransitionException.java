package com.stepflow.domain;

public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Enum<?> from, Enum<?> to) {
        super("Cannot move from " + from + " to " + to);
    }
}
