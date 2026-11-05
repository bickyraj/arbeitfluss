CREATE TABLE executions (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_definition JSONB NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    trigger_input JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_workflow_execution_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_executions_workflow_id ON executions (workflow_id);
CREATE INDEX idx_executions_status ON executions (status);
CREATE INDEX idx_executions_created_at ON executions (created_at DESC);
CREATE INDEX idx_executions_workflow_created ON executions (workflow_id, created_at DESC);

CREATE TABLE node_executions (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    workflow_node_id VARCHAR(255) NOT NULL,
    node_type VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_data JSONB,
    output_data JSONB,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_node_execution_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT uq_node_execution_id UNIQUE (execution_id, workflow_node_id)
);

CREATE INDEX idx_node_executions_execution_id ON node_executions (execution_id);
CREATE INDEX idx_node_executions_status ON node_executions (status);

CREATE TABLE execution_logs (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    node_execution_id UUID REFERENCES node_executions(id) ON DELETE SET NULL,
    level VARCHAR(16) NOT NULL,
    message TEXT NOT NULL,
    details JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_execution_log_level CHECK (level IN ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'))
);

CREATE INDEX idx_execution_logs_execution_order
    ON execution_logs (execution_id, created_at, id);
CREATE INDEX idx_execution_logs_node_execution_id ON execution_logs (node_execution_id);
