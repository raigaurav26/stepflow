package com.stepflow.worker;

import com.stepflow.run.StepExecution;
import com.stepflow.run.StepQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Polls the step queue and runs claimed steps on a fixed number of threads.
 *
 * <p>The poller only claims as many steps as it has free threads, so a busy instance
 * leaves work in the queue for other instances instead of hoarding leases.
 */
@Component
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final StepQueue queue;
    private final StepExecutor executor;
    private final WorkerProperties props;
    private final String workerId;
    private final Semaphore freeSlots;

    private volatile boolean running;
    private ExecutorService steps;
    private Thread poller;

    public WorkerPool(StepQueue queue, StepExecutor executor, WorkerProperties props) {
        this.queue = queue;
        this.executor = executor;
        this.props = props;
        this.workerId = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.freeSlots = new Semaphore(props.threads());
    }

    public String workerId() {
        return workerId;
    }

    /** Claims and runs whatever is runnable right now on the calling thread. Returns how many ran. */
    public int drainOnce(int max) {
        List<StepExecution> claimed = queue.claim(workerId, max, props.leaseDuration());
        claimed.forEach(step -> runSafely(step));
        return claimed.size();
    }

    @Override
    public void start() {
        if (!props.enabled()) {
            log.info("Worker pool disabled (stepflow.worker.enabled=false)");
            return;
        }
        running = true;
        steps = Executors.newFixedThreadPool(props.threads(), r -> new Thread(r, "stepflow-step"));
        poller = new Thread(this::pollLoop, "stepflow-poller");
        poller.setDaemon(true);
        poller.start();
        log.info("Worker {} started with {} threads", workerId, props.threads());
    }

    private void pollLoop() {
        while (running) {
            try {
                freeSlots.acquire();
                int free = 1 + freeSlots.drainPermits();
                List<StepExecution> claimed = queue.claim(workerId, free, props.leaseDuration());
                freeSlots.release(free - claimed.size());
                for (StepExecution step : claimed) {
                    steps.submit(() -> {
                        try {
                            runSafely(step);
                        } finally {
                            freeSlots.release();
                        }
                    });
                }
                if (claimed.isEmpty()) {
                    Thread.sleep(props.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Polling the step queue failed, backing off", e);
                sleepQuietly();
            }
        }
    }

    private void runSafely(StepExecution step) {
        try {
            executor.execute(step, workerId);
        } catch (RuntimeException e) {
            // the lease will expire and the step becomes claimable again
            log.error("Could not record the result of step {} of run {}", step.stepName(), step.runId(), e);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(props.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
        if (steps != null) {
            steps.shutdown();
            try {
                if (!steps.awaitTermination(10, TimeUnit.SECONDS)) {
                    steps.shutdownNow();
                }
            } catch (InterruptedException e) {
                steps.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
