package com.stepflow.run;

import com.stepflow.domain.RunStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record WorkflowRun(UUID id,
                          String workflowType,
                          RunStatus status,
                          Map<String, Object> input,
                          Map<String, Object> output,
                          String error,
                          String idempotencyKey,
                          Instant createdAt,
                          Instant updatedAt,
                          Instant completedAt) {
}
