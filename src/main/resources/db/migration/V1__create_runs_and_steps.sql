-- A workflow run: one execution of a named workflow with its input.
CREATE TABLE workflow_runs (
    id               UUID PRIMARY KEY,
    workflow_type    VARCHAR(100) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    input            JSONB,
    output           JSONB,
    error            TEXT,
    -- callers can pass a key so retrying "start" never creates a second run
    idempotency_key  VARCHAR(200) UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT chk_run_status CHECK (status IN
        ('PENDING', 'RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_runs_type_status ON workflow_runs (workflow_type, status);

-- One row per step of a run. Workers claim rows from this table like a queue.
CREATE TABLE step_executions (
    id                BIGSERIAL PRIMARY KEY,
    run_id            UUID         NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    step_index        INT          NOT NULL,
    step_name         VARCHAR(100) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    attempt           INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 3,
    output            JSONB,
    last_error        TEXT,
    -- when the step may next be picked up (used for retry backoff and durable timers)
    next_run_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lease_owner       VARCHAR(100),
    lease_expires_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_run_step UNIQUE (run_id, step_index),
    CONSTRAINT chk_step_status CHECK (status IN
        ('PENDING', 'LEASED', 'SUCCEEDED', 'RETRY_SCHEDULED', 'DEAD', 'COMPENSATED')),
    CONSTRAINT chk_attempts CHECK (attempt >= 0 AND max_attempts >= 1)
);

-- The worker poll query only looks at runnable rows, so keep that index small.
CREATE INDEX idx_steps_runnable ON step_executions (next_run_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

-- Used to find steps whose worker died while holding the lease.
CREATE INDEX idx_steps_leased ON step_executions (lease_expires_at)
    WHERE status = 'LEASED';
