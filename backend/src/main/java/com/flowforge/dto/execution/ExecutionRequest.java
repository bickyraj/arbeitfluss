package com.flowforge.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ExecutionRequest(
        JsonNode triggerInput,
        @Min(1) @Max(3600) Integer timeoutSeconds
) {
}
