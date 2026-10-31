package com.stepflow.api;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record StartRunRequest(@NotBlank String workflowType, Map<String, Object> input) {
}
