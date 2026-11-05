package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.runtime.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class LogMessageExecutor implements NodeExecutor {
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern CREDENTIAL_ASSIGNMENT =
            Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|password|secret)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");

    @Override
    public String nodeType() {
        return "log-message";
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        JsonNode configuration = context.nodeConfiguration();
        JsonNode messageNode = configuration == null ? null : configuration.get("message");
        if (messageNode == null || !messageNode.isTextual()) {
            throw new NodeExecutionException("Log Message requires a string message");
        }

        String safeMessage = redact(messageNode.textValue());
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.put("workflowNodeId", context.workflowNodeId());
        context.log(ExecutionLogLevel.INFO, safeMessage, details);
        return input == null ? JsonNodeFactory.instance.nullNode() : input.deepCopy();
    }

    private String redact(String message) {
        return CREDENTIAL_ASSIGNMENT.matcher(BEARER_TOKEN.matcher(message).replaceAll("$1[REDACTED]"))
                .replaceAll("$1[REDACTED]");
    }
}
