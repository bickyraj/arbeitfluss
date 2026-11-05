package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.ai.AiConfigurationPolicy;
import com.flowforge.ai.AiProviderException;
import com.flowforge.ai.AiProviderRegistry;
import com.flowforge.ai.AiRequest;
import com.flowforge.ai.AiResponse;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.runtime.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiModelExecutor implements NodeExecutor {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*(input|upstream)(?:\\.([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*))?\\s*}}", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    private final AiProviderRegistry providers;
    private final String defaultModel;
    private final int defaultTimeoutSeconds;

    public AiModelExecutor(AiProviderRegistry providers,
                           @Value("${FLOWFORGE_OPENAI_DEFAULT_MODEL:gpt-4.1-mini}") String defaultModel,
                           @Value("${FLOWFORGE_AI_TIMEOUT_SECONDS:30}") int defaultTimeoutSeconds) {
        this.providers = providers;
        this.defaultModel = defaultModel;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    @Override
    public String nodeType() { return "ai-model"; }

    @Override
    public boolean abortOnWorkflowCancellation() { return true; }

    @Override
    public void validateConfiguration(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            throw invalid("AI Model configuration must be an object.");
        }
        try {
            AiConfigurationPolicy.rejectCredentialFields(configuration);
        } catch (com.flowforge.exception.InvalidExecutionRequestException exception) {
            throw invalid("AI Model configuration contains an unsupported field.");
        }
        String provider = text(configuration, "provider", "openai");
        if (!providers.supports(provider)) throw invalid("AI Model provider is not supported.");
        String model = text(configuration, "model", defaultModel);
        if (!MODEL_ID.matcher(model).matches()) throw invalid("AI Model identifier is invalid.");
        String system = text(configuration, "systemPrompt", "");
        String user = text(configuration, "userPrompt", "");
        if (system.length() > 16_000) throw invalid("AI Model system prompt is too long.");
        if (user.isBlank() || user.length() > 32_000) throw invalid("AI Model requires a user prompt of at most 32000 characters.");
        validateTemplate(system); validateTemplate(user);
        JsonNode temperature = configuration.get("temperature");
        if (temperature != null && (!temperature.isNumber() || !Double.isFinite(temperature.asDouble())
                || temperature.asDouble() < 0 || temperature.asDouble() > 2)) {
            throw invalid("AI Model temperature must be between 0 and 2.");
        }
        int tokens = integer(configuration, "maxOutputTokens", 512);
        if (tokens < 1 || tokens > 8192) throw invalid("AI Model output token limit must be between 1 and 8192.");
        int timeout = integer(configuration, "timeoutSeconds", defaultTimeoutSeconds);
        if (timeout < 1 || timeout > 120) throw invalid("AI Model timeout must be between 1 and 120 seconds.");
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        JsonNode configuration = context.nodeConfiguration();
        validateConfiguration(configuration);
        String providerId = text(configuration, "provider", "openai");
        String model = text(configuration, "model", defaultModel);
        String system = render(text(configuration, "systemPrompt", ""), input, context.upstreamOutputs(), 16_000);
        String user = render(text(configuration, "userPrompt", ""), input, context.upstreamOutputs(), 32_000);
        if (user.isBlank()) throw invalid("AI Model user prompt resolved to empty text.");
        if (context.isCancellationRequested()) throw new NodeExecutionException("AI Model request was cancelled.", "AI_CANCELLED");
        if (context.isExpired()) throw new NodeExecutionException("AI Model request timed out.", "AI_TIMEOUT");
        int timeout = Math.min(integer(configuration, "timeoutSeconds", defaultTimeoutSeconds),
                Math.max(1, (int) Math.ceil(context.remainingTimeMillis() / 1000.0)));
        try {
            AiResponse response = providers.require(providerId).generate(new AiRequest(model, system, user,
                    configuration.path("temperature").isNumber() ? configuration.path("temperature").asDouble() : 0.7,
                    integer(configuration, "maxOutputTokens", 512), timeout));
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.put("text", response.text());
            output.put("provider", response.provider());
            output.put("model", response.model());
            ObjectNode details = JsonNodeFactory.instance.objectNode()
                    .put("provider", response.provider()).put("model", response.model());
            if (response.usage() != null) {
                ObjectNode usage = output.putObject("usage");
                addCount(usage, "inputTokens", response.usage().inputTokens());
                addCount(usage, "outputTokens", response.usage().outputTokens());
                addCount(usage, "totalTokens", response.usage().totalTokens());
                addCount(details, "totalTokens", response.usage().totalTokens());
            }
            context.log(ExecutionLogLevel.INFO, "AI Model request completed.", details);
            return output;
        } catch (AiProviderException exception) {
            throw new NodeExecutionException(exception.getMessage(), exception.code());
        }
    }

    private void addCount(ObjectNode node, String name, Integer count) {
        if (count != null) node.put(name, count);
    }

    private String render(String template, JsonNode input, Map<String, JsonNode> upstream, int limit) {
        Matcher matcher = TEMPLATE.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String scope = matcher.group(1).toLowerCase();
            String[] path = matcher.group(2) == null ? new String[0] : matcher.group(2).split("\\.");
            JsonNode value;
            int offset;
            if ("input".equals(scope)) { value = input; offset = 0; }
            else {
                if (path.length == 0) throw invalid("AI Model upstream reference requires a node ID.");
                value = upstream.get(path[0]); offset = 1;
            }
            for (int i = offset; i < path.length && value != null; i++) {
                value = value.path(path[i]);
            }
            if (value == null || value.isMissingNode() || value.isNull()) {
                throw new NodeExecutionException("AI Model prompt references missing workflow data.", "AI_PROMPT_INPUT_MISSING");
            }
            String replacement = value.isTextual() ? value.textValue() : value.toString();
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
            if (rendered.length() > limit) throw invalid("AI Model resolved prompt is too long.");
        }
        matcher.appendTail(rendered);
        if (rendered.length() > limit) throw invalid("AI Model resolved prompt is too long.");
        return rendered.toString();
    }

    private void validateTemplate(String template) {
        String unmatched = TEMPLATE.matcher(template).replaceAll("");
        if (unmatched.contains("{{") || unmatched.contains("}}")) {
            throw invalid("AI Model prompt contains an invalid workflow reference.");
        }
    }

    private String text(JsonNode config, String key, String fallback) {
        JsonNode value = config.get(key);
        if (value == null) return fallback;
        if (!value.isTextual()) throw invalid("AI Model " + key + " must be text.");
        return value.textValue();
    }

    private int integer(JsonNode config, String key, int fallback) {
        JsonNode value = config.get(key);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid("AI Model " + key + " must be a whole number.");
        return value.intValue();
    }

    private NodeExecutionException invalid(String message) {
        return new NodeExecutionException(message, "AI_CONFIGURATION_INVALID");
    }
}
