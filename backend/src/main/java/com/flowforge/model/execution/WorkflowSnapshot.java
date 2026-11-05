package com.flowforge.model.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record WorkflowSnapshot(UUID workflowId, String workflowName, JsonNode definition) {
    public WorkflowSnapshot {
        definition = definition == null ? null : definition.deepCopy();
    }

    @Override
    public JsonNode definition() {
        return definition == null ? null : definition.deepCopy();
    }
}
