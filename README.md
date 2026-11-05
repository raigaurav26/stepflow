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
| Step execution | `step_executions` | One row per step (`ACTION`) or per undo (`COMPENSATION`); workers claim runnable rows like a queue |

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

## Saga rollback

Some steps can't be wrapped in one database transaction because they call other systems: a flight is booked, a hotel is booked, then the card is declined. A saga undoes the steps that already happened instead. Give a step a compensation:

```java
WorkflowDefinition.builder("trip-booking")
        .step("book-flight", ctx -> airline.book(ctx.input()))
        .compensateWith(ctx -> airline.cancel(ctx.outputOf("book-flight")))
        .step("book-hotel", ctx -> hotels.book(ctx.input()))
        .compensateWith(ctx -> hotels.cancel(ctx.outputOf("book-hotel")))
        .step("send-itinerary", ctx -> email.send(ctx.input()))   // nothing to undo
        .step("charge-card", ctx -> payments.charge(ctx.input()))
        .build();
```

If `charge-card` fails for good, the run moves to `COMPENSATING` and the hotel is cancelled, then the flight: newest first, skipping steps with nothing to undo. Then the run is `COMPENSATED` and keeps the original error.

- Compensations are rows in the same queue (`kind = 'COMPENSATION'`), so they get the same leasing, retries, backoff and crash safety as normal steps.
- Each compensation is queued in the same transaction that records the previous one, so a crash mid-rollback picks up where it stopped.
- If a compensation runs out of attempts, the rollback stops and the run is `FAILED` with both errors. Undoing further could make things worse, so the remaining steps are left for a person to check.

## API

| Method | Path | What it does |
|---|---|---|
| GET | `/api/workflows` | Registered workflows and their steps |
| POST | `/api/runs` | Start a run: `{"workflowType": "order-fulfilment", "input": {...}}` → `201` with `Location` |
| GET | `/api/runs/{id}` | A run with all of its steps |
| GET | `/api/runs?limit=20` | Most recent runs |

### Idempotent starts

A client that times out on `POST /api/runs` can't tell whether the run started. Sending the request again could pay someone twice. With an `Idempotency-Key` header, it's safe to resend:

```bash
curl -X POST localhost:8080/api/runs \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: payout-77' \
  -d '{"workflowType": "payout", "input": {"amount": 1500}}'
```

| Request | Response |
|---|---|
| First request with a key | `201 Created`, new run |
| Same key, same workflow and input | `200 OK`, the original run, `Idempotent-Replayed: true` |
| Same key, different workflow or input | `409 Conflict` with `existingRunId` |
| No key | `201 Created`, always a new run |

The insert uses `ON CONFLICT (idempotency_key) DO NOTHING` on a unique column rather than check-then-insert, so requests racing with the same key can't both create a run. A test sends 12 at once and gets exactly one run and one queued step.

Starting a run creates it as `PENDING` and queues only its first step. Each later step is queued when the one before it succeeds, so the step table only ever holds work that can run now. Errors use RFC 7807 problem details (`404` unknown run, `422` unknown workflow type, `409` idempotency key reused, `400` invalid body).

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

## Retries

A failed step goes to `RETRY_SCHEDULED` with `next_run_at` set in the future, so the wait survives restarts and no thread sleeps through it. The wait grows exponentially, is capped, and has jitter so that many steps failing together (a partner API going down) don't all retry in the same instant:

```
wait(n) = min(initialDelay * multiplier^(n-1), maxDelay) * (1 - jitter * random)
```

With the defaults (1s, ×2, 5m cap, 20% jitter) the waits are about 1s, 2s, 4s, 8s … up to 5 minutes. A step can set its own policy:

```java
.step("charge-card", 5, RetryPolicy.exponential(Duration.ofSeconds(2), Duration.ofMinutes(1)), ctx -> ...)
```

Throw `NonRetryableException` when trying again can't help (a declined card, invalid input). The step goes straight to `DEAD` without using up its attempts.

| Setting | Default | |
|---|---|---|
| `stepflow.worker.enabled` | `true` | Turn the pool off for API-only instances |
| `stepflow.worker.threads` | `4` | Steps run at the same time per instance |
| `stepflow.worker.poll-interval` | `500ms` | Wait between polls when the queue is empty |
| `stepflow.worker.lease-duration` | `30s` | How long a claimed step belongs to one worker |
| `stepflow.retry.initial-delay` | `1s` | Wait before the first retry |
| `stepflow.retry.multiplier` | `2.0` | Growth of the wait after each failure |
| `stepflow.retry.max-delay` | `5m` | Longest wait between attempts |
| `stepflow.retry.jitter` | `0.2` | Up to this share of the wait is randomly removed |

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
- [x] Retries with exponential backoff and jitter, dead steps
- [x] Saga compensation in reverse order
- [x] Idempotent run start (`Idempotency-Key` header)
- [ ] Durable timers (`sleep` steps) and scheduled runs
- [ ] Lease expiry recovery when a worker crashes mid-step
- [ ] Chaos tests: kill workers mid-run and check nothing is lost or run twice
- [ ] Metrics with Micrometer and Prometheus
- [ ] Web dashboard of runs and steps
- [ ] Demo workflow: order fulfilment
