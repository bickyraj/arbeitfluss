package com.flowforge.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.model.execution.ExecutionLogLevel;

import java.time.Instant;
import java.util.UUID;

public record ExecutionLogResponse(
        UUID id,
        UUID executionId,
        UUID nodeExecutionId,
        ExecutionLogLevel level,
        String message,
        JsonNode details,
        Instant createdAt
) {
    public static ExecutionLogResponse fromEntity(ExecutionLog log) {
        return new ExecutionLogResponse(
                log.getId(),
                log.getExecutionId(),
                log.getNodeExecutionId(),
                log.getLevel(),
                log.getMessage(),
                log.getDetails(),
                log.getCreatedAt()
        );
    }
}
