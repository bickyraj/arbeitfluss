CREATE TABLE webhook_registrations (
    id UUID PRIMARY KEY,
    webhook_id VARCHAR(64) NOT NULL UNIQUE,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    node_id VARCHAR(255) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    authentication_mode VARCHAR(20) NOT NULL,
    secret_hash VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_webhook_registration_workflow_node UNIQUE (workflow_id, node_id),
    CONSTRAINT chk_webhook_method CHECK (http_method IN ('POST', 'GET', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT chk_webhook_authentication CHECK (authentication_mode IN ('NONE', 'SECRET_TOKEN'))
);

CREATE INDEX idx_webhook_registrations_workflow ON webhook_registrations (workflow_id);
