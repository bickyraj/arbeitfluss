package com.flowforge.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateWorkflowRequest(
        @NotBlank(message = "must not be blank") String name,
        String description,
        Boolean active,
        @NotNull(message = "definition is required") JsonNode definition
) {
}
