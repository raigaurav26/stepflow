# StepFlow

[![CI](https://github.com/raigaurav26/stepflow/actions/workflows/ci.yml/badge.svg)](https://github.com/raigaurav26/stepflow/actions/workflows/ci.yml)

A durable workflow engine built on Spring Boot and PostgreSQL.

You describe a business process (for example *reserve stock → charge card → ship order*) as a list of steps. StepFlow stores every run and every step in PostgreSQL, so a process survives crashes and restarts. Failed steps are retried with backoff, and if a step finally gives up, the steps that already succeeded are undone in reverse order (the saga pattern).

The idea is similar to Temporal or AWS Step Functions, written from scratch to show how they work underneath: database-backed queues, leases, retries, idempotency and compensation.

## Tech stack

Java 17 · Spring Boot 3 · PostgreSQL 16 · Flyway · JdbcTemplate · JUnit 5 · GitHub Actions

## How it works (design)

| Concept | Table | Notes |
|---|---|---|
| Workflow run | `workflow_runs` | One execution of a workflow, with input, output and an optional idempotency key |
| Step execution | `step_executions` | One row per step; workers claim runnable rows like a queue |

Run lifecycle:

```
PENDING → RUNNING → COMPLETED
                  → COMPENSATING → COMPENSATED
                  → FAILED
PENDING / RUNNING → CANCELLED
```

Step lifecycle:

```
PENDING → LEASED → SUCCEEDED → COMPENSATED
             ↓  ↑
       RETRY_SCHEDULED
             ↓
           DEAD
LEASED → PENDING   (lease expired: the worker crashed)
```

Status transitions are enforced in code (`RunStatus`, `StepStatus`) and status values by `CHECK` constraints in the schema.

## Defining a workflow

Register a `WorkflowDefinition` bean. Each step returns a map that is saved as JSON and passed to later steps.

```java
@Bean
WorkflowDefinition orderFulfilment(InventoryClient inventory, PaymentClient payments) {
    return WorkflowDefinition.builder("order-fulfilment")
            .step("reserve-stock", ctx -> inventory.reserve(ctx.input()))
            .step("charge-card", 5, ctx -> payments.charge(ctx.outputOf("reserve-stock")))
            .step("ship", ctx -> Map.of("trackingId", "TRK-1"))
            .build();
}
```

The number after the step name is how many attempts it gets (default 3). Steps should be safe to run twice, because a retry or a crashed worker can repeat one.

## API

| Method | Path | What it does |
|---|---|---|
| GET | `/api/workflows` | Registered workflows and their steps |
| POST | `/api/runs` | Start a run: `{"workflowType": "order-fulfilment", "input": {...}}` → `201` with `Location` |
| GET | `/api/runs/{id}` | A run with all of its steps |
| GET | `/api/runs?limit=20` | Most recent runs |

Starting a run creates it as `PENDING` and queues only its first step. Each later step is queued when the one before it succeeds, so the step table only ever holds work that can run now. Errors use RFC 7807 problem details (`404` unknown run, `422` unknown workflow type, `400` invalid body).

## Workers

Each instance runs a small worker pool (`WorkerPool`). A poller thread claims runnable steps and hands them to a fixed number of threads:

```sql
UPDATE step_executions SET status = 'LEASED', lease_owner = ?, lease_expires_at = now() + ?, attempt = attempt + 1
 WHERE id IN (SELECT id FROM step_executions
               WHERE status IN ('PENDING', 'RETRY_SCHEDULED') AND next_run_at <= now()
               ORDER BY next_run_at LIMIT ?
                 FOR UPDATE SKIP LOCKED)
RETURNING *;
```

- `SKIP LOCKED` lets many workers, across many instances, poll the same table without blocking each other or taking the same step twice. A test runs 8 workers against 300 steps and checks each is claimed exactly once.
- The poller only claims as many steps as it has free threads, so a busy instance leaves work for the others.
- Every result is written with `WHERE lease_owner = ?`. A worker that lost its lease can't overwrite the new owner's result.
- When a step succeeds, the next step is queued in the same transaction. After the last step, the run is `COMPLETED` with that step's output.
- A failing step is retried until it runs out of attempts. Then it is `DEAD` and the run is `FAILED` with the error.

| Setting | Default | |
|---|---|---|
| `stepflow.worker.enabled` | `true` | Turn the pool off for API-only instances |
| `stepflow.worker.threads` | `4` | Steps run at the same time per instance |
| `stepflow.worker.poll-interval` | `500ms` | Wait between polls when the queue is empty |
| `stepflow.worker.lease-duration` | `30s` | How long a claimed step belongs to one worker |

## Running locally

```bash
docker compose up -d      # PostgreSQL on localhost:5432
mvn spring-boot:run       # Flyway creates the schema on startup
curl localhost:8080/actuator/health
```

Tests need the same PostgreSQL (`docker compose up -d`, then `mvn verify`). CI starts it as a service container.

Connection settings can be overridden with `STEPFLOW_DB_URL`, `STEPFLOW_DB_USER` and `STEPFLOW_DB_PASSWORD`.

## Roadmap

- [x] Project setup, schema and status state machines
- [x] Workflow definition API and `POST /api/runs` to start a run
- [x] Worker pool that claims steps with `SELECT ... FOR UPDATE SKIP LOCKED`
- [ ] Retries with exponential backoff and jitter, dead steps
- [ ] Saga compensation in reverse order
- [ ] Idempotent run start (`Idempotency-Key` header)
- [ ] Durable timers (`sleep` steps) and scheduled runs
- [ ] Lease expiry recovery when a worker crashes mid-step
- [ ] Chaos tests: kill workers mid-run and check nothing is lost or run twice
- [ ] Metrics with Micrometer and Prometheus
- [ ] Web dashboard of runs and steps
- [ ] Demo workflow: order fulfilment
