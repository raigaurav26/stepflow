package com.stepflow.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RunStatusTest {

    @Test
    void happyPathIsAllowed() {
        assertTrue(RunStatus.PENDING.canTransitionTo(RunStatus.RUNNING));
        assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.COMPLETED));
    }

    @Test
    void failedRunCanBeRolledBack() {
        assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.COMPENSATING));
        assertTrue(RunStatus.COMPENSATING.canTransitionTo(RunStatus.COMPENSATED));
    }

    @Test
    void cannotSkipStraightToCompleted() {
        assertFalse(RunStatus.PENDING.canTransitionTo(RunStatus.COMPLETED));
        assertThrows(IllegalTransitionException.class,
                () -> Transitions.check(RunStatus.PENDING, RunStatus.COMPLETED));
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"COMPLETED", "COMPENSATED", "FAILED", "CANCELLED"})
    void terminalStatesGoNowhere(RunStatus terminal) {
        assertTrue(terminal.isTerminal());
        for (RunStatus next : RunStatus.values()) {
            assertFalse(terminal.canTransitionTo(next), terminal + " -> " + next);
        }
    }
}
