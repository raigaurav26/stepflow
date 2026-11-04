package com.stepflow.run;

import com.stepflow.domain.StepKind;
import com.stepflow.domain.StepStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record StepExecution(long id,
                            UUID runId,
                            int stepIndex,
                            String stepName,
                            StepKind kind,
                            StepStatus status,
                            int attempt,
                            int maxAttempts,
                            Map<String, Object> output,
                            String lastError,
                            Instant nextRunAt,
                            String leaseOwner,
                            Instant leaseExpiresAt) {
}
