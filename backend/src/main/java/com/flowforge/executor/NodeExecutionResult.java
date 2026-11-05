package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;

public record NodeExecutionResult(JsonNode output, String selectedOutputHandle, JsonNode decisionDetails) {
    public NodeExecutionResult {
        output = output == null ? null : output.deepCopy();
        decisionDetails = decisionDetails == null ? null : decisionDetails.deepCopy();
    }

    @Override
    public JsonNode output() {
        return output == null ? null : output.deepCopy();
    }

    @Override
    public JsonNode decisionDetails() {
        return decisionDetails == null ? null : decisionDetails.deepCopy();
    }

    public static NodeExecutionResult output(JsonNode output) {
        return new NodeExecutionResult(output, null, null);
    }
}
