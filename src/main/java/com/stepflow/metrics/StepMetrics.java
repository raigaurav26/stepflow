package com.stepflow.metrics;

import com.stepflow.domain.RunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Everything StepFlow reports about its own work. Tags are the workflow type and step name,
 * which come from code and so stay few; ids never appear in tags.
 *
 * <pre>
 * stepflow_step_duration_seconds{workflow,step,kind,outcome}   handler runtime and count
 * stepflow_step_retries_total{workflow,step}                    attempts that failed and will be retried
 * stepflow_step_dead_total{workflow,step,kind}                  steps that ran out of attempts
 * stepflow_lease_lost_total{workflow,step}                      results dropped because the lease was gone
 * stepflow_leases_reclaimed_total                               steps taken back from crashed workers
 * stepflow_runs_started_total{workflow}
 * stepflow_runs_finished_total{workflow,status}                 COMPLETED, COMPENSATED or FAILED
 * </pre>
 */
@Component
public class StepMetrics {

    private final MeterRegistry registry;

    public StepMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome {@code succeeded} or {@code failed}; a crash never gets this far, which is how it shows up as a gap */
    public void stepRan(String workflow, String step, String kind, String outcome, Duration took) {
        Timer.builder("stepflow.step.duration")
                .description("How long step handlers ran")
                .tags("workflow", workflow, "step", step, "kind", kind.toLowerCase(), "outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(took);
    }

    public void stepRetried(String workflow, String step) {
        registry.counter("stepflow.step.retries", "workflow", workflow, "step", step).increment();
    }

    public void stepDead(String workflow, String step, String kind) {
        registry.counter("stepflow.step.dead", "workflow", workflow, "step", step, "kind", kind.toLowerCase())
                .increment();
    }

    public void leaseLost(String workflow, String step) {
        registry.counter("stepflow.lease.lost", "workflow", workflow, "step", step).increment();
    }

    public void leasesReclaimed(int count) {
        registry.counter("stepflow.leases.reclaimed").increment(count);
    }

    public void runStarted(String workflow) {
        registry.counter("stepflow.runs.started", "workflow", workflow).increment();
    }

    public void runFinished(String workflow, RunStatus status) {
        registry.counter("stepflow.runs.finished", "workflow", workflow, "status", status.name()).increment();
    }
}
