ALTER TABLE executions DROP CONSTRAINT chk_workflow_execution_status;
ALTER TABLE executions
    ADD CONSTRAINT chk_workflow_execution_status
    CHECK (status IN ('QUEUED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

ALTER TABLE executions ADD COLUMN error_code VARCHAR(64);
ALTER TABLE executions ADD COLUMN timeout_seconds INTEGER NOT NULL DEFAULT 300;
ALTER TABLE executions ADD COLUMN retry_of_execution_id UUID REFERENCES executions(id);

CREATE INDEX idx_executions_retry_of ON executions (retry_of_execution_id);
