package com.flowforge.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.entity.NodeExecution;
import com.flowforge.model.execution.NodeExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record NodeExecutionResponse(
        UUID id,
        UUID executionId,
        String workflowNodeId,
        String nodeType,
        NodeExecutionStatus status,
        JsonNode inputData,
        JsonNode outputData,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorMessage,
        Instant createdAt
) {
    public static NodeExecutionResponse fromEntity(NodeExecution nodeExecution) {
        return new NodeExecutionResponse(
                nodeExecution.getId(),
                nodeExecution.getExecutionId(),
                nodeExecution.getWorkflowNodeId(),
                nodeExecution.getNodeType(),
                nodeExecution.getStatus(),
                nodeExecution.getInputData(),
                nodeExecution.getOutputData(),
                nodeExecution.getStartedAt(),
                nodeExecution.getCompletedAt(),
                nodeExecution.getDurationMs(),
                nodeExecution.getErrorMessage(),
                nodeExecution.getCreatedAt()
        );
    }
}
