package com.flowforge.model.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ExecutionRunSnapshot(
        UUID executionId,
        UUID workflowId,
        JsonNode definition,
        JsonNode triggerInput,
        int timeoutSeconds
) {
    public ExecutionRunSnapshot {
        definition = definition.deepCopy();
        triggerInput = triggerInput.deepCopy();
    }

    public ExecutionRunSnapshot(UUID executionId, UUID workflowId, JsonNode definition, JsonNode triggerInput) {
        this(executionId, workflowId, definition, triggerInput, 300);
    }

    @Override
    public JsonNode definition() {
        return definition.deepCopy();
    }

    @Override
    public JsonNode triggerInput() {
        return triggerInput.deepCopy();
    }
}
