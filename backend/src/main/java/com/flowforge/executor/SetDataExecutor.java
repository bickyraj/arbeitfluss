package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.flowforge.runtime.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class SetDataExecutor implements NodeExecutor {
    @Override
    public String nodeType() {
        return "set-data";
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        JsonNode configuration = context.nodeConfiguration();
        JsonNode keyNode = configuration == null ? null : configuration.get("key");
        JsonNode value = configuration == null ? null : configuration.get("value");
        if (keyNode == null || !keyNode.isTextual() || keyNode.textValue().isBlank() || value == null) {
            throw new NodeExecutionException("Set Data requires a non-empty key and a value");
        }

        ObjectNode output;
        if (input != null && input.isObject()) {
            output = (ObjectNode) input.deepCopy();
        } else {
            output = JsonNodeFactory.instance.objectNode();
            if (input != null && !input.isNull()) {
                output.set("input", input.deepCopy());
            }
        }
        output.set(keyNode.textValue(), value.deepCopy());
        return output;
    }
}
