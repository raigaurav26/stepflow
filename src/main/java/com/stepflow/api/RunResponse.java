package com.stepflow.api;

import com.stepflow.domain.RunStatus;
import com.stepflow.run.StepExecution;
import com.stepflow.run.WorkflowRun;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunResponse(UUID id,
                          String workflowType,
                          RunStatus status,
                          Map<String, Object> input,
                          Map<String, Object> output,
                          String error,
                          Instant createdAt,
                          Instant completedAt,
                          List<StepExecution> steps) {

    public static RunResponse of(WorkflowRun run, List<StepExecution> steps) {
        return new RunResponse(run.id(), run.workflowType(), run.status(), run.input(), run.output(),
                run.error(), run.createdAt(), run.completedAt(), steps);
    }
}
