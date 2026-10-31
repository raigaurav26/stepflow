package com.stepflow.run;

import com.stepflow.definition.StepDefinition;
import com.stepflow.definition.WorkflowDefinition;
import com.stepflow.definition.WorkflowRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RunService {

    private final WorkflowRegistry registry;
    private final RunRepository runs;

    public RunService(WorkflowRegistry registry, RunRepository runs) {
        this.registry = registry;
        this.runs = runs;
    }

    /**
     * Creates a PENDING run and queues its first step. Later steps are queued one at a
     * time as the step before them succeeds, so the queue only ever holds runnable work.
     */
    @Transactional
    public WorkflowRun start(String workflowType, Map<String, Object> input) {
        WorkflowDefinition definition = registry.get(workflowType);
        UUID runId = UUID.randomUUID();
        runs.insertRun(runId, definition.type(), input == null ? Map.of() : input, null);

        StepDefinition first = definition.step(0);
        runs.insertStep(runId, 0, first.name(), first.maxAttempts());

        return get(runId);
    }

    public WorkflowRun get(UUID runId) {
        return runs.findRun(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    public List<StepExecution> steps(UUID runId) {
        get(runId);
        return runs.findSteps(runId);
    }

    public List<WorkflowRun> recent(int limit) {
        return runs.findRecentRuns(Math.max(1, Math.min(limit, 100)));
    }
}
