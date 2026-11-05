package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.runtime.ExecutionContext;

public interface NodeExecutor {
    String nodeType();

    JsonNode execute(JsonNode input, ExecutionContext context) throws Exception;
}
