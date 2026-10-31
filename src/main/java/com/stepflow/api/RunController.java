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

    @PostMapping("/runs")
    public ResponseEntity<RunResponse> start(@Valid @RequestBody StartRunRequest request) {
        WorkflowRun run = runService.start(request.workflowType(), request.input());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(run.id()).toUri();
        return ResponseEntity.created(location).body(RunResponse.of(run, runService.steps(run.id())));
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
