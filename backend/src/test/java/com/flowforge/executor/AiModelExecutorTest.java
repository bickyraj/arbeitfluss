package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.ai.AiProvider;
import com.flowforge.ai.AiProviderRegistry;
import com.flowforge.ai.AiRequest;
import com.flowforge.ai.AiResponse;
import com.flowforge.ai.AiUsage;
import com.flowforge.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesConfigurationBeforeCallingProvider() {
        AtomicReference<AiRequest> captured = new AtomicReference<>();
        AiModelExecutor executor = executor(captured);
        ObjectNode config = config();
        config.put("temperature", 3);
        assertThatThrownBy(() -> executor.execute(mapper.createObjectNode(), context(config, Map.of(), new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(error -> assertThat(((NodeExecutionException) error).errorCode()).isEqualTo("AI_CONFIGURATION_INVALID"));
        assertThat(captured.get()).isNull();

        config.put("temperature", 0.5);
        config.put("maxOutputTokens", 0);
        assertThatThrownBy(() -> executor.validateConfiguration(config)).isInstanceOf(NodeExecutionException.class);
        config.put("maxOutputTokens", 64);
        config.put("userPrompt", "{{input.missing}}");
        assertThatThrownBy(() -> executor.execute(mapper.createObjectNode(), context(config, Map.of(), new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(error -> assertThat(((NodeExecutionException) error).errorCode()).isEqualTo("AI_PROMPT_INPUT_MISSING"));
        assertThat(captured.get()).isNull();
    }

    @Test
    void expandsWorkflowInputAndUpstreamOutputThenPassesNormalizedResultDownstream() {
        AtomicReference<AiRequest> captured = new AtomicReference<>();
        AiModelExecutor executor = executor(captured);
        ObjectNode config = config();
        config.put("systemPrompt", "Use {{input.topic}}.");
        config.put("userPrompt", "Summarize {{upstream.node-1.text}} for {{input.customer}}.");
        List<String> logs = new ArrayList<>();
        JsonNode input = mapper.createObjectNode().put("topic", "brief notes").put("customer", "Ada");
        JsonNode upstream = mapper.createObjectNode().put("text", "Quarterly update");

        JsonNode aiOutput = executor.execute(input, context(config, Map.of("node-1", upstream), logs));
        assertThat(captured.get().systemMessage()).isEqualTo("Use brief notes.");
        assertThat(captured.get().userMessage()).isEqualTo("Summarize Quarterly update for Ada.");
        assertThat(captured.get().temperature()).isEqualTo(0.5);
        assertThat(aiOutput.path("text").asText()).isEqualTo("Generated answer");
        assertThat(aiOutput.path("usage").path("totalTokens").asInt()).isEqualTo(12);

        ObjectNode downstreamConfig = mapper.createObjectNode().put("key", "processed").put("value", true);
        JsonNode downstreamOutput = new SetDataExecutor().execute(aiOutput,
                context(downstreamConfig, Map.of("ai-node", aiOutput), new ArrayList<>()));
        assertThat(downstreamOutput.path("text").asText()).isEqualTo("Generated answer");
        assertThat(downstreamOutput.path("processed").asBoolean()).isTrue();
        assertThat(logs).hasSize(1).allSatisfy(log -> assertThat(log)
                .doesNotContain("Quarterly update", "Ada", "Generated answer"));
    }

    private AiModelExecutor executor(AtomicReference<AiRequest> captured) {
        AiProvider provider = new AiProvider() {
            public String id() { return "openai"; }
            public AiResponse generate(AiRequest request) {
                captured.set(request);
                return new AiResponse("openai", request.model(), "Generated answer", new AiUsage(7, 5, 12));
            }
        };
        return new AiModelExecutor(new AiProviderRegistry(List.of(provider)), "gpt-4.1-mini", 30);
    }

    private ObjectNode config() {
        return mapper.createObjectNode().put("provider", "openai").put("model", "gpt-4.1-mini")
                .put("systemPrompt", "").put("userPrompt", "Hello {{input}}")
                .put("temperature", 0.5).put("maxOutputTokens", 64);
    }

    private ExecutionContext context(JsonNode config, Map<String, JsonNode> upstream, List<String> logs) {
        return new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ai-node", config,
                upstream, Instant.now().plusSeconds(60), () -> false,
                (level, message, details) -> logs.add(message + details));
    }
}
