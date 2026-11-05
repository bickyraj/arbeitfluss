package com.flowforge.dto.execution;

import com.flowforge.model.execution.ExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record ExecutionStartResponse(UUID id, ExecutionStatus status, Instant createdAt) {
}
