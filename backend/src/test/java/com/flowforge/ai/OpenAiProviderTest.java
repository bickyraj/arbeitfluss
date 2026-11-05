package com.flowforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiProviderTest {
    private static final String TEST_ONLY_CREDENTIAL = "test-only-ai-provider-credential";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    private OpenAiProvider provider(String key) {
        return new OpenAiProvider(key, endpoint,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), mapper);
    }

    private AiRequest request() {
        return new AiRequest("gpt-4.1-mini", "Be concise.", "Hello", 0.4, 120, 2);
    }

    @Test
    void constructsResponsesRequestAndMapsTextAndUsage() throws Exception {
        AtomicReference<JsonNode> posted = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            posted.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"status":"completed","model":"gpt-4.1-mini","output":[{"type":"message","content":[{"type":"output_text","text":"Hello back"}]}],"usage":{"input_tokens":9,"output_tokens":3,"total_tokens":12}}
                    """);
        });

        AiResponse response = provider(TEST_ONLY_CREDENTIAL).generate(request());

        assertThat(authorization.get()).isEqualTo("Bearer " + TEST_ONLY_CREDENTIAL);
        assertThat(posted.get().path("model").asText()).isEqualTo("gpt-4.1-mini");
        assertThat(posted.get().path("store").asBoolean()).isFalse();
        assertThat(posted.get().path("temperature").asDouble()).isEqualTo(0.4);
        assertThat(posted.get().path("max_output_tokens").asInt()).isEqualTo(120);
        assertThat(posted.get().path("input").get(0).path("role").asText()).isEqualTo("system");
        assertThat(posted.get().path("input").get(1).path("role").asText()).isEqualTo("user");
        assertThat(response).isEqualTo(new AiResponse("openai", "gpt-4.1-mini", "Hello back", new AiUsage(9, 3, 12)));
        assertThat(response.toString()).doesNotContain(TEST_ONLY_CREDENTIAL);
    }

    @Test
    void missingCredentialFailsBeforeHttpRequest() {
        assertThatThrownBy(() -> provider(" ").generate(request()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> assertThat(((AiProviderException) error).code()).isEqualTo("AI_NOT_CONFIGURED"));
    }

    @Test
    void authenticationAndRateLimitErrorsAreNormalizedWithoutProviderBody() {
        server.createContext("/v1/responses", exchange -> respond(exchange, 401,
                "{\"error\":\"" + TEST_ONLY_CREDENTIAL + "\"}"));
        assertSafeCode("AI_AUTH_FAILED");
        server.removeContext("/v1/responses");
        server.createContext("/v1/responses", exchange -> respond(exchange, 429,
                "{\"error\":\"" + TEST_ONLY_CREDENTIAL + "\"}"));
        assertSafeCode("AI_RATE_LIMITED");
    }

    @Test
    void timeoutAndMalformedResponseDoNotExposeProviderDetails() {
        server.createContext("/v1/responses", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, "{}"); } catch (IOException ignored) { }
        });
        AiRequest shortRequest = new AiRequest("gpt-4.1-mini", "", "Hello", 0.4, 120, 1);
        assertThatThrownBy(() -> provider(TEST_ONLY_CREDENTIAL).generate(shortRequest))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> assertThat(((AiProviderException) error).code()).isEqualTo("AI_TIMEOUT"));
        server.removeContext("/v1/responses");
        server.createContext("/v1/responses", exchange -> respond(exchange, 200,
                "{\"status\":\"completed\",\"output\":[],\"secret\":\"" + TEST_ONLY_CREDENTIAL + "\"}"));
        assertSafeCode("AI_RESPONSE_INVALID");
    }

    private void assertSafeCode(String code) {
        assertThatThrownBy(() -> provider(TEST_ONLY_CREDENTIAL).generate(request()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> {
                    assertThat(((AiProviderException) error).code()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(TEST_ONLY_CREDENTIAL);
                });
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
