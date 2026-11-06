-- Recurring runs: start a workflow every time a cron expression fires.
CREATE TABLE schedules (
    id             UUID PRIMARY KEY,
    name           VARCHAR(100) NOT NULL UNIQUE,
    workflow_type  VARCHAR(100) NOT NULL,
    cron           VARCHAR(100) NOT NULL,
    zone           VARCHAR(50)  NOT NULL DEFAULT 'UTC',
    input          JSONB,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    next_fire_at   TIMESTAMPTZ  NOT NULL,
    last_fired_at  TIMESTAMPTZ,
    last_run_id    UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedules_due ON schedules (next_fire_at) WHERE enabled;
