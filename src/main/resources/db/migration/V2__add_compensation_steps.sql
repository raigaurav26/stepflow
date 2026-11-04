-- Compensation (undo) work goes through the same queue as normal steps, so it gets the
-- same leasing, retries and crash safety. A row is either the step itself (ACTION) or
-- the undo of an earlier successful step with the same index (COMPENSATION).
ALTER TABLE step_executions
    ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'ACTION',
    ADD CONSTRAINT chk_step_kind CHECK (kind IN ('ACTION', 'COMPENSATION'));

ALTER TABLE step_executions DROP CONSTRAINT uq_run_step;
ALTER TABLE step_executions ADD CONSTRAINT uq_run_step_kind UNIQUE (run_id, step_index, kind);
