package com.flowforge.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.entity.Workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        String name,
        String description,
        boolean active,
        JsonNode definition,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowResponse fromEntity(Workflow workflow) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.isActive(),
                workflow.getDefinition(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }
}
