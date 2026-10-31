package com.stepflow.api;

import com.stepflow.definition.StepDefinition;
import com.stepflow.definition.WorkflowDefinition;

import java.util.List;

public record WorkflowSummary(String type, List<Step> steps) {

    public record Step(String name, int maxAttempts) {
    }

    public static WorkflowSummary of(WorkflowDefinition definition) {
        List<Step> steps = definition.steps().stream()
                .map((StepDefinition s) -> new Step(s.name(), s.maxAttempts()))
                .toList();
        return new WorkflowSummary(definition.type(), steps);
    }
}
