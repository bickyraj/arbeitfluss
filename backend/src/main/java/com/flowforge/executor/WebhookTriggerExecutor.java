package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.flowforge.runtime.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class WebhookTriggerExecutor implements NodeExecutor {
    @Override
    public String nodeType() {
        return "webhook-trigger";
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        return input == null ? NullNode.getInstance() : input.deepCopy();
    }
}
