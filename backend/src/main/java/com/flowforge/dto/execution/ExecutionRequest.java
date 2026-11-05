package com.flowforge.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;

public record ExecutionRequest(JsonNode triggerInput) {
}
