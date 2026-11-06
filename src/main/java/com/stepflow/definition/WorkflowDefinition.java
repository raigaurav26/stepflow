package com.stepflow.definition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A named, ordered list of steps. Register one as a Spring bean to make it startable:
 *
 * <pre>{@code
 * @Bean
 * WorkflowDefinition orderFulfilment() {
 *     return WorkflowDefinition.builder("order-fulfilment")
 *             .step("reserve-stock", ctx -> inventory.reserve(ctx.input()))
 *             .compensateWith(ctx -> inventory.release(ctx.outputOf("reserve-stock")))
 *             .step("charge-card", 5, RetryPolicy.exponential(Duration.ofSeconds(2), Duration.ofMinutes(1)),
 *                     ctx -> payments.charge(ctx.input()))
 *             .build();
 * }
 * }</pre>
 */
public final class WorkflowDefinition {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String type;
    private final List<StepDefinition> steps;

    private WorkflowDefinition(String type, List<StepDefinition> steps) {
        this.type = type;
        this.steps = List.copyOf(steps);
    }

    public static Builder builder(String type) {
        return new Builder(type);
    }

    public String type() {
        return type;
    }

    public List<StepDefinition> steps() {
        return steps;
    }

    public StepDefinition step(int index) {
        return steps.get(index);
    }

    public Optional<StepDefinition> stepNamed(String name) {
        return steps.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public static final class Builder {

        private final String type;
        private final List<StepDefinition> steps = new ArrayList<>();

        private Builder(String type) {
            if (type == null || !type.matches("[a-z0-9][a-z0-9-]{0,99}")) {
                throw new IllegalArgumentException(
                        "Workflow type must be lowercase letters, digits and dashes: " + type);
            }
            this.type = type;
        }

        public Builder step(String name, StepHandler handler) {
            return step(name, DEFAULT_MAX_ATTEMPTS, handler);
        }

        public Builder step(String name, int maxAttempts, StepHandler handler) {
            return step(name, maxAttempts, null, handler);
        }

        public Builder step(String name, int maxAttempts, RetryPolicy retryPolicy, StepHandler handler) {
            steps.add(new StepDefinition(name, maxAttempts, retryPolicy, handler, null, null));
            return this;
        }

        /**
         * A durable timer: the run waits {@code duration} before the next step. Nothing sleeps
         * while it waits; the step simply isn't claimable until its time comes, so the wait
         * survives restarts and can last days (a cooling-off period, a reminder email).
         */
        public Builder sleep(String name, Duration duration) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("sleep needs a positive duration");
            }
            steps.add(new StepDefinition(name, 1, null, ctx -> Map.of("sleptSeconds", duration.toSeconds()),
                    null, duration));
            return this;
        }

        /**
         * Sets how to undo the step added just before this call. If a later step fails for
         * good, compensations of the steps that already succeeded run in reverse order. The
         * compensation can read the step's own output with {@code ctx.outputOf(stepName)}.
         */
        public Builder compensateWith(StepHandler compensation) {
            if (steps.isEmpty()) {
                throw new IllegalStateException("compensateWith must follow a step");
            }
            int last = steps.size() - 1;
            StepDefinition step = steps.get(last);
            if (step.hasCompensation()) {
                throw new IllegalStateException("Step " + step.name() + " already has a compensation");
            }
            steps.set(last, step.withCompensation(Objects.requireNonNull(compensation)));
            return this;
        }

        public WorkflowDefinition build() {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("Workflow " + type + " has no steps");
            }
            Set<String> names = new HashSet<>();
            for (StepDefinition step : steps) {
                if (!names.add(step.name())) {
                    throw new IllegalArgumentException(
                            "Workflow " + type + " has two steps named " + step.name());
                }
            }
            return new WorkflowDefinition(type, steps);
        }
    }
}
