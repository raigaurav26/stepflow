package com.stepflow.api;

import com.stepflow.definition.WorkflowRegistry;
import com.stepflow.run.RunService;
import com.stepflow.run.WorkflowRun;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runService;
    private final WorkflowRegistry registry;

    public RunController(RunService runService, WorkflowRegistry registry) {
        this.runService = runService;
        this.registry = registry;
    }

    @GetMapping("/workflows")
    public List<WorkflowSummary> workflows() {
        return registry.all().stream().map(WorkflowSummary::of).toList();
    }

    /**
     * Starts a run. With an {@code Idempotency-Key} header, repeating the same request returns
     * the original run with {@code 200} and {@code Idempotent-Replayed: true} instead of
     * starting another one.
     */
    @PostMapping("/runs")
    public ResponseEntity<RunResponse> start(@Valid @RequestBody StartRunRequest request,
                                             @RequestHeader(name = "Idempotency-Key", required = false)
                                             String idempotencyKey) {
        RunService.StartResult result = runService.start(request.workflowType(), request.input(), idempotencyKey);
        WorkflowRun run = result.run();
        RunResponse body = RunResponse.of(run, runService.steps(run.id()));
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/runs/{id}").buildAndExpand(run.id()).toUri();
        if (!result.created()) {
            return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/runs/{id}")
    public RunResponse get(@PathVariable UUID id) {
        return RunResponse.of(runService.get(id), runService.steps(id));
    }

    @GetMapping("/runs")
    public List<RunResponse> recent(@RequestParam(defaultValue = "20") int limit) {
        return runService.recent(limit).stream()
                .map(run -> RunResponse.of(run, List.of()))
                .toList();
    }
}
