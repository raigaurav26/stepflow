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
- [ ] Worker loop that claims steps with `SELECT ... FOR UPDATE SKIP LOCKED`
- [ ] Retries with exponential backoff and jitter, dead steps
- [ ] Saga compensation in reverse order
- [ ] Idempotent run start (`Idempotency-Key` header)
- [ ] Durable timers (`sleep` steps) and scheduled runs
- [ ] Lease expiry recovery when a worker crashes mid-step
- [ ] Chaos tests: kill workers mid-run and check nothing is lost or run twice
- [ ] Metrics with Micrometer and Prometheus
- [ ] Web dashboard of runs and steps
- [ ] Demo workflow: order fulfilment
