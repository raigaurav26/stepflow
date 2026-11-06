package com.stepflow.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionTest {

    private static final StepHandler NOOP = ctx -> Map.of();

    @Test
    void keepsStepsInOrderWithDefaultAttempts() {
        WorkflowDefinition definition = WorkflowDefinition.builder("order-fulfilment")
                .step("reserve-stock", NOOP)
                .step("charge-card", 5, NOOP)
                .build();

        assertEquals(List.of("reserve-stock", "charge-card"),
                definition.steps().stream().map(StepDefinition::name).toList());
        assertEquals(WorkflowDefinition.DEFAULT_MAX_ATTEMPTS, definition.step(0).maxAttempts());
        assertEquals(5, definition.stepNamed("charge-card").orElseThrow().maxAttempts());
    }

    @Test
    void rejectsWorkflowWithoutSteps() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinition.builder("empty").build());
    }

    @Test
    void rejectsDuplicateStepNames() {
        WorkflowDefinition.Builder builder = WorkflowDefinition.builder("dupes")
                .step("send-email", NOOP)
                .step("send-email", NOOP);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void rejectsBadTypeNames() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinition.builder("Order Fulfilment"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinition.builder(""));
    }

    @Test
    void rejectsZeroAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinition.builder("x").step("only", 0, NOOP));
    }

    @Test
    void stepsCannotBeModifiedAfterBuild() {
        WorkflowDefinition definition = WorkflowDefinition.builder("x").step("only", NOOP).build();
        assertThrows(UnsupportedOperationException.class, () -> definition.steps().clear());
    }

    @Test
    void compensationAttachesToTheStepBeforeIt() {
        StepHandler undo = ctx -> Map.of();
        WorkflowDefinition definition = WorkflowDefinition.builder("x")
                .step("reserve", NOOP).compensateWith(undo)
                .step("notify", NOOP)
                .build();

        assertSame(undo, definition.step(0).compensation());
        assertFalse(definition.step(1).hasCompensation());
    }

    @Test
    void compensationNeedsAStepAndOnlyOnePerStep() {
        assertThrows(IllegalStateException.class,
                () -> WorkflowDefinition.builder("x").compensateWith(NOOP));
        assertThrows(IllegalStateException.class,
                () -> WorkflowDefinition.builder("x").step("a", NOOP).compensateWith(NOOP).compensateWith(NOOP));
    }

    @Test
    void sleepAddsADelayedStep() {
        WorkflowDefinition definition = WorkflowDefinition.builder("x")
                .step("a", NOOP)
                .sleep("wait", java.time.Duration.ofDays(3))
                .build();

        assertEquals(java.time.Duration.ofDays(3), definition.step(1).delay());
        assertEquals(java.time.Duration.ZERO, definition.step(0).delayOrZero());
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinition.builder("y").sleep("wait", java.time.Duration.ZERO));
    }

    @Test
    void contextGivesOutputOfEarlierStep() {
        StepContext ctx = new StepContext(null, Map.of(),
                Map.of("reserve-stock", Map.of("reservationId", "r-1")), 1);
        assertEquals("r-1", ctx.outputOf("reserve-stock").get("reservationId"));
        assertThrows(IllegalStateException.class, () -> ctx.outputOf("charge-card"));
    }
}
