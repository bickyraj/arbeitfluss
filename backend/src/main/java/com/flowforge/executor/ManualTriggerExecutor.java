package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.runtime.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class ManualTriggerExecutor implements NodeExecutor {
    @Override
    public String nodeType() {
        return "manual-trigger";
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        return input == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : input.deepCopy();
    }
}
