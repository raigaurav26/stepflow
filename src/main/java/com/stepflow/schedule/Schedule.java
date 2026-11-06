package com.stepflow.schedule;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Schedule(UUID id,
                       String name,
                       String workflowType,
                       String cron,
                       String zone,
                       Map<String, Object> input,
                       boolean enabled,
                       Instant nextFireAt,
                       Instant lastFiredAt,
                       UUID lastRunId) {
}
