package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.runtime.ExecutionContext;

public interface NodeExecutor {
    String nodeType();

    default void validateConfiguration(JsonNode configuration) {
    }

    JsonNode execute(JsonNode input, ExecutionContext context) throws Exception;

    default NodeExecutionResult executeResult(JsonNode input, ExecutionContext context) throws Exception {
        return NodeExecutionResult.output(execute(input, context));
    }
}
