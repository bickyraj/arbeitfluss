package com.flowforge.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateWorkflowRequest(
        String name,
        String description,
        Boolean active,
        JsonNode definition
) {
}
