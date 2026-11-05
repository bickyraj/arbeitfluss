package com.flowforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.exception.InvalidExecutionRequestException;

import java.util.Set;

/** The AI node never accepts credential fields in persisted workflow configuration. */
public final class AiConfigurationPolicy {
    private static final Set<String> ALLOWED = Set.of(
            "provider", "model", "systemPrompt", "userPrompt", "temperature", "maxOutputTokens", "timeoutSeconds");

    private AiConfigurationPolicy() {}

    public static void rejectCredentialConfiguration(JsonNode definition) {
        JsonNode nodes = definition == null ? null : definition.path("nodes");
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            if (!"ai-model".equals(node.path("type").asText())) continue;
            rejectCredentialFields(node.path("configuration"));
        }
    }

    public static void rejectCredentialFields(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) return;
        configuration.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED.contains(field)) {
                throw new InvalidExecutionRequestException(
                        "AI Model configuration contains an unsupported field. Credentials must be configured on the server.");
            }
            if (configuration.path(field).isContainerNode()) {
                throw new InvalidExecutionRequestException(
                        "AI Model configuration values must be scalar. Credentials must be configured on the server.");
            }
        });
    }
}
