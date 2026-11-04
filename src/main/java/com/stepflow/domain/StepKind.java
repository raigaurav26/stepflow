package com.stepflow.domain;

public enum StepKind {
    /** The step's own work. */
    ACTION,
    /** Undoing the work of an earlier ACTION that succeeded, during saga rollback. */
    COMPENSATION
}
