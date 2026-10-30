package com.stepflow.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepStatusTest {

    @Test
    void retryLoopIsAllowed() {
        assertTrue(StepStatus.PENDING.canTransitionTo(StepStatus.LEASED));
        assertTrue(StepStatus.LEASED.canTransitionTo(StepStatus.RETRY_SCHEDULED));
        assertTrue(StepStatus.RETRY_SCHEDULED.canTransitionTo(StepStatus.LEASED));
        assertTrue(StepStatus.LEASED.canTransitionTo(StepStatus.DEAD));
    }

    @Test
    void expiredLeaseReturnsStepToPending() {
        assertTrue(StepStatus.LEASED.canTransitionTo(StepStatus.PENDING));
    }

    @Test
    void onlySucceededStepsCanBeCompensated() {
        assertTrue(StepStatus.SUCCEEDED.canTransitionTo(StepStatus.COMPENSATED));
        assertFalse(StepStatus.DEAD.canTransitionTo(StepStatus.COMPENSATED));
        assertFalse(StepStatus.PENDING.canTransitionTo(StepStatus.COMPENSATED));
    }

    @Test
    void pendingStepCannotSucceedWithoutBeingLeased() {
        assertThrows(IllegalTransitionException.class,
                () -> Transitions.check(StepStatus.PENDING, StepStatus.SUCCEEDED));
    }
}
