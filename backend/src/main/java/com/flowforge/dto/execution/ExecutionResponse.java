package com.flowforge.dto.execution;

import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionTriggerType;
import com.flowforge.model.execution.ExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record ExecutionResponse(
        UUID id,
        UUID workflowId,
        String workflowName,
        ExecutionTriggerType triggerType,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorMessage,
        Instant createdAt
) {
    public static ExecutionResponse fromEntity(Execution execution) {
        return new ExecutionResponse(
                execution.getId(),
                execution.getWorkflowId(),
                execution.getWorkflowName(),
                execution.getTriggerType(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getDurationMs(),
                execution.getErrorMessage(),
                execution.getCreatedAt()
        );
    }
}
