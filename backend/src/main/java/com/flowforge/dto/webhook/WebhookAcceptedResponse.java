package com.flowforge.dto.webhook;

import com.flowforge.model.execution.ExecutionStatus;

import java.util.UUID;

public record WebhookAcceptedResponse(boolean accepted, UUID executionId, ExecutionStatus status) {
}
