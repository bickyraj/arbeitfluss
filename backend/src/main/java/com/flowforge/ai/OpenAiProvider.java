package com.flowforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class OpenAiProvider implements AiProvider {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final String apiKey;
    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;

    @Autowired
    public OpenAiProvider(@Value("${OPENAI_API_KEY:}") String apiKey, ObjectMapper mapper) {
        this(apiKey, RESPONSES_URI,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), mapper);
    }

    OpenAiProvider(String apiKey, URI endpoint, HttpClient client, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String id() { return "openai"; }

    @Override
    public AiResponse generate(AiRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiProviderException("AI_NOT_CONFIGURED", "OpenAI is not configured on the server.");
        }
        if (apiKey.chars().anyMatch(character -> character <= 32 || character >= 127)) {
            throw new AiProviderException("AI_AUTH_FAILED", "AI provider rejected the server credential.");
        }
        if (request == null || request.model() == null || request.model().isBlank()
                || request.userMessage() == null || request.userMessage().isBlank()
                || !Double.isFinite(request.temperature()) || request.temperature() < 0 || request.temperature() > 2
                || request.maxOutputTokens() < 1 || request.maxOutputTokens() > 8192
                || request.timeoutSeconds() < 1 || request.timeoutSeconds() > 120) {
            throw new AiProviderException("AI_CONFIGURATION_INVALID", "AI provider request configuration is invalid.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("max_output_tokens", request.maxOutputTokens());
        body.put("store", false);
        ArrayNode input = body.putArray("input");
        if (request.systemMessage() != null && !request.systemMessage().isBlank()) {
            input.addObject().put("role", "system").put("content", request.systemMessage());
        }
        input.addObject().put("role", "user").put("content", request.userMessage());
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw statusFailure(response.statusCode());
                }
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new AiProviderException("AI_RESPONSE_INVALID", "AI provider response exceeded the size limit.");
                }
                return mapResponse(mapper.readTree(bytes), request.model());
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new AiProviderException("AI_TIMEOUT", "AI provider request timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("AI_CANCELLED", "AI provider request was interrupted.");
        } catch (IOException exception) {
            throw new AiProviderException("AI_PROVIDER_UNAVAILABLE", "AI provider request failed.");
        }
    }

    private AiProviderException statusFailure(int status) {
        if (status == 401 || status == 403) {
            return new AiProviderException("AI_AUTH_FAILED", "AI provider rejected the server credential.");
        }
        if (status == 429) {
            return new AiProviderException("AI_RATE_LIMITED", "AI provider rate limit was reached.");
        }
        if (status == 408 || status == 504) {
            return new AiProviderException("AI_TIMEOUT", "AI provider request timed out.");
        }
        if (status >= 500) {
            return new AiProviderException("AI_PROVIDER_UNAVAILABLE", "AI provider is unavailable.");
        }
        return new AiProviderException("AI_PROVIDER_ERROR", "AI provider rejected the request.");
    }

    private AiResponse mapResponse(JsonNode response, String requestedModel) {
        if (response == null || !response.isObject() || !"completed".equals(response.path("status").asText())) {
            throw new AiProviderException("AI_RESPONSE_INVALID", "AI provider returned an incomplete response.");
        }
        StringBuilder text = new StringBuilder();
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            throw new AiProviderException("AI_RESPONSE_INVALID", "AI provider returned no message output.");
        }
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText()) || !item.path("content").isArray()) continue;
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText()) && content.path("text").isTextual()) {
                    text.append(content.path("text").textValue());
                }
            }
        }
        if (text.isEmpty()) {
            throw new AiProviderException("AI_RESPONSE_INVALID", "AI provider returned no text output.");
        }
        JsonNode usageNode = response.path("usage");
        AiUsage usage = usageNode.isObject() ? new AiUsage(
                tokenCount(usageNode, "input_tokens"), tokenCount(usageNode, "output_tokens"),
                tokenCount(usageNode, "total_tokens")) : null;
        String model = response.path("model").isTextual() && !response.path("model").asText().isBlank()
                ? response.path("model").asText() : requestedModel;
        return new AiResponse(id(), model, text.toString(), usage);
    }

    private Integer tokenCount(JsonNode usage, String key) {
        JsonNode value = usage.path(key);
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue() : null;
    }
}
