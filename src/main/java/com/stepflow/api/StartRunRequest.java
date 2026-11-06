package com.stepflow.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * @param startAt optional ISO-8601 time to start the run at, for example "2026-11-07T09:00:00+05:30"
 */
public record StartRunRequest(@NotBlank String workflowType, Map<String, Object> input, Instant startAt) {
}
