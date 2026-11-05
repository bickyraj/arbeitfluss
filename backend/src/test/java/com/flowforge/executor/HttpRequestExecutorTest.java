package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.runtime.ExecutionContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRequestExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private HttpRequestExecutor executor;
    private int port;

    @BeforeEach
    void startLocalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.start();
        port = server.getAddress().getPort();
        OutboundAddressPolicy testOnlyLoopbackPolicy = new OutboundAddressPolicy() {
            @Override
            InetAddress[] resolvePublicAddresses(String hostname) throws UnknownHostException {
                if ("127.0.0.1".equals(hostname)) {
                    return new InetAddress[]{InetAddress.getByName(hostname)};
                }
                if ("localhost".equals(hostname)) {
                    return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                }
                return super.resolvePublicAddresses(hostname);
            }
        };
        executor = new HttpRequestExecutor(objectMapper, testOnlyLoopbackPolicy);
    }

    @AfterEach
    void stopLocalServer() throws IOException {
        if (executor != null) executor.close();
        if (server != null) server.stop(0);
    }

    @Test
    void getsAndParsesJsonWithEncodedQueryAndCustomHeader() {
        AtomicReference<String> requestQuery = new AtomicReference<>();
        AtomicReference<String> requestHeader = new AtomicReference<>();
        server.createContext("/users", exchange -> {
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            requestHeader.set(exchange.getRequestHeaders().getFirst("X-Client"));
            respond(exchange, 200, "application/json", "{\"id\":7}");
        });
        ObjectNode config = configuration("GET", "/users")
                .put("responseFormat", "auto");
        config.putArray("queryParameters")
                .addObject().put("name", "search").put("value", "Alex & Mina").put("enabled", true);
        config.putArray("headers")
                .addObject().put("name", "X-Client").put("value", "Flow Forge").put("enabled", true);

        JsonNode output = executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>()));

        assertThat(requestQuery.get()).isEqualTo("search=Alex%20%26%20Mina");
        assertThat(requestHeader.get()).isEqualTo("Flow Forge");
        assertThat(output.path("body").path("id").asInt()).isEqualTo(7);
        assertThat(output.path("statusCode").asInt()).isEqualTo(200);
        assertThat(output.path("success").asBoolean()).isTrue();
    }

    @Test
    void postsJsonBodyAndSupportsPlainTextResponses() {
        AtomicReference<String> postedBody = new AtomicReference<>();
        server.createContext("/submit", exchange -> {
            postedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/plain", "accepted");
        });
        ObjectNode config = configuration("POST", "/submit")
                .put("responseFormat", "text")
                .put("bodyType", "json");
        config.set("body", objectMapper.createObjectNode().put("active", true));

        JsonNode output = executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>()));

        assertThat(postedBody.get()).isEqualTo("{\"active\":true}");
        assertThat(output.path("body").asText()).isEqualTo("accepted");
    }

    @Test
    void preservesHttpErrorStatusAndSafeResponseBodyOnFailedNode() {
        server.createContext("/missing", exchange -> respond(exchange, 404, "application/json", "{\"error\":\"missing\"}"));
        ObjectNode config = configuration("GET", "/missing");

        assertThatThrownBy(() -> executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(exception -> {
                    NodeExecutionException nodeException = (NodeExecutionException) exception;
                    assertThat(nodeException.errorCode()).isEqualTo("HTTP_STATUS_ERROR");
                    assertThat(nodeException.failureOutput().path("statusCode").asInt()).isEqualTo(404);
                    assertThat(nodeException.failureOutput().path("body").path("error").asText()).isEqualTo("missing");
                });
    }

    @Test
    void treatsServerErrorsAsFailedExecutionsAndRetainsSafeStatusDetails() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/unavailable", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "text/plain", "temporarily unavailable");
        });

        assertThatThrownBy(() -> executor.execute(
                objectMapper.createObjectNode(),
                context(configuration("GET", "/unavailable"), new ArrayList<>())
        )).isInstanceOf(NodeExecutionException.class)
                .satisfies(exception -> {
                    NodeExecutionException nodeException = (NodeExecutionException) exception;
                    assertThat(nodeException.errorCode()).isEqualTo("HTTP_STATUS_ERROR");
                    assertThat(nodeException.failureOutput().path("statusCode").asInt()).isEqualTo(503);
                    assertThat(nodeException.failureOutput().path("body").asText())
                            .isEqualTo("temporarily unavailable");
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void passesHttpResponseOutputIntoASetDataDownstreamNode() {
        server.createContext("/user", exchange ->
                respond(exchange, 200, "application/json", "{\"name\":\"Alex\"}"));
        JsonNode httpOutput = executor.execute(
                objectMapper.createObjectNode(),
                context(configuration("GET", "/user"), new ArrayList<>())
        );
        ObjectNode setDataConfig = objectMapper.createObjectNode()
                .put("key", "httpResult")
                .set("value", httpOutput.path("body").deepCopy());
        ExecutionContext downstreamContext = context(setDataConfig, new ArrayList<>());

        JsonNode downstreamOutput = new SetDataExecutor().execute(httpOutput, downstreamContext);

        assertThat(downstreamOutput.path("statusCode").asInt()).isEqualTo(200);
        assertThat(downstreamOutput.path("httpResult").path("name").asText()).isEqualTo("Alex");
    }

    @Test
    void reportsConnectionFailuresWithoutLeakingTransportDetails() {
        server.stop(0);

        assertThatThrownBy(() -> executor.execute(
                objectMapper.createObjectNode(),
                context(configuration("GET", "/"), new ArrayList<>())
        )).isInstanceOf(NodeExecutionException.class)
                .satisfies(exception ->
                        assertThat(((NodeExecutionException) exception).errorCode())
                                .isEqualTo("HTTP_CONNECTION_FAILURE"));
    }

    @Test
    void reportsDnsFailuresWithoutExposingResolverMessages() {
        OutboundAddressPolicy unavailableDns = new OutboundAddressPolicy() {
            @Override
            InetAddress[] resolvePublicAddresses(String hostname) throws UnknownHostException {
                throw new UnknownHostException("private resolver message");
            }
        };
        try (HttpRequestExecutor dnsFailureExecutor = new HttpRequestExecutor(objectMapper, unavailableDns)) {
            ObjectNode config = configuration("GET", "/");
            config.put("url", "https://unavailable.example.test/");

            assertThatThrownBy(() -> dnsFailureExecutor.execute(
                    objectMapper.createObjectNode(),
                    context(config, new ArrayList<>())
            )).isInstanceOf(NodeExecutionException.class)
                    .satisfies(exception -> {
                        NodeExecutionException nodeException = (NodeExecutionException) exception;
                        assertThat(nodeException.errorCode()).isEqualTo("HTTP_DNS_FAILURE");
                        assertThat(nodeException.getMessage()).doesNotContain("private resolver message");
                    });
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void includesBoundedResponseDetailsWhenExplicitJsonParsingFails() {
        server.createContext("/invalid-json", exchange -> respond(exchange, 200, "text/plain", "not-json"));
        ObjectNode config = configuration("GET", "/invalid-json").put("responseFormat", "json");

        assertThatThrownBy(() -> executor.execute(
                objectMapper.createObjectNode(),
                context(config, new ArrayList<>())
        )).isInstanceOf(NodeExecutionException.class)
                .satisfies(exception -> {
                    NodeExecutionException nodeException = (NodeExecutionException) exception;
                    assertThat(nodeException.errorCode()).isEqualTo("INVALID_RESPONSE_FORMAT");
                    assertThat(nodeException.failureOutput().path("statusCode").asInt()).isEqualTo(200);
                    assertThat(nodeException.failureOutput().path("body").asText()).isEqualTo("not-json");
                });
    }

    @Test
    void rejectsPrivateDestinationsUnsupportedSchemesAndCredentialUrls() throws IOException {
        ObjectNode privateAddress = configuration("GET", "/").put("url", "http://127.0.0.1:" + port + "/");
        ObjectNode fileUrl = configuration("GET", "/").put("url", "file:///etc/passwd");
        ObjectNode credentialUrl = configuration("GET", "/").put("url", "https://user:pass@example.com/");

        assertThatCode(() -> executor.validateConfiguration(privateAddress)).doesNotThrowAnyException();
        assertThatThrownBy(() -> executor.validateConfiguration(fileUrl))
                .isInstanceOf(NodeExecutionException.class);
        assertThatThrownBy(() -> executor.validateConfiguration(credentialUrl))
                .isInstanceOf(NodeExecutionException.class);
        try (HttpRequestExecutor productionExecutor = new HttpRequestExecutor(objectMapper)) {
            assertThatThrownBy(() -> productionExecutor.execute(
                    objectMapper.createObjectNode(),
                    context(privateAddress, new ArrayList<>())
            )).isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("publicly routable");
        }
    }

    @Test
    void validatesRedirectDestinationsAndDoesNotFollowToMetadataAddress() {
        AtomicReference<String> requestCount = new AtomicReference<>("0");
        server.createContext("/redirect", exchange -> {
            requestCount.set("1");
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        ObjectNode config = configuration("GET", "/redirect").put("followRedirects", true);

        assertThatThrownBy(() -> executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("publicly routable");
        assertThat(requestCount.get()).isEqualTo("1");
    }

    @Test
    void stripsCredentialHeadersAfterCrossHostRedirects() {
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/redirect-host", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "text/plain", "done");
        });
        ObjectNode config = configuration("GET", "/redirect-host").put("followRedirects", true);
        config.putArray("headers")
                .addObject().put("name", "Authorization").put("value", "Bearer private-token").put("enabled", true);

        JsonNode output = executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>()));

        assertThat(output.path("success").asBoolean()).isTrue();
        assertThat(authorization.get()).isNull();
    }

    @Test
    void rejectsDnsAnswersThatChangeToPrivateAddressesBeforeConnecting() {
        AtomicReference<Integer> lookupCount = new AtomicReference<>(0);
        OutboundAddressPolicy rebindingPolicy = new OutboundAddressPolicy() {
            @Override
            InetAddress[] resolvePublicAddresses(String hostname) throws UnknownHostException {
                if (lookupCount.getAndSet(lookupCount.get() + 1) == 0) {
                    return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                }
                throw new SSRFBlockedException();
            }
        };
        try (HttpRequestExecutor rebindingExecutor = new HttpRequestExecutor(objectMapper, rebindingPolicy)) {
            ObjectNode config = configuration("GET", "/");
            config.put("url", "http://rebind.example.test:" + port + "/");
            assertThatThrownBy(() -> rebindingExecutor.execute(
                    objectMapper.createObjectNode(),
                    context(config, new ArrayList<>())
            )).isInstanceOf(NodeExecutionException.class)
                    .satisfies(exception ->
                    assertThat(((NodeExecutionException) exception).errorCode()).isEqualTo("SSRF_BLOCKED"));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        assertThat(lookupCount.get()).isEqualTo(2);
    }

    @Test
    void failsResponseLargerThanFiveMegabytes() {
        server.createContext("/large", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 5L * 1024 * 1024 + 1);
            exchange.getResponseBody().write(new byte[5 * 1024 * 1024 + 1]);
            exchange.close();
        });
        ObjectNode config = configuration("GET", "/large").put("responseFormat", "text");

        assertThatThrownBy(() -> executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(exception ->
                        assertThat(((NodeExecutionException) exception).errorCode()).isEqualTo("RESPONSE_TOO_LARGE"));
    }

    @Test
    void enforcesConfiguredRequestTimeout() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1500);
                respond(exchange, 200, "text/plain", "late");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        ObjectNode config = configuration("GET", "/slow").put("timeoutSeconds", 1);

        assertThatThrownBy(() -> executor.execute(objectMapper.createObjectNode(), context(config, new ArrayList<>())))
                .isInstanceOf(NodeExecutionException.class)
                .satisfies(exception ->
                        assertThat(((NodeExecutionException) exception).errorCode()).isEqualTo("HTTP_TIMEOUT"));
    }

    @Test
    void cancellationAbortsTheInFlightRequestAndDoesNotLogSecrets() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        server.createContext("/wait", exchange -> {
            requestStarted.countDown();
            try {
                releaseServer.await(2, TimeUnit.SECONDS);
                respond(exchange, 200, "application/json", "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        ObjectNode config = configuration("GET", "/wait");
        config.putArray("headers")
                .addObject().put("name", "Authorization").put("value", "Bearer top-secret").put("enabled", true);
        List<String> logs = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutionContext context = context(config, logs);
        Thread requestThread = Thread.ofVirtual().start(() -> {
            try {
                executor.execute(objectMapper.createObjectNode(), context);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
        context.cancelActiveOperations();
        requestThread.join(1500);
        releaseServer.countDown();

        assertThat(requestThread.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(NodeExecutionException.class);
        assertThat(((NodeExecutionException) failure.get()).errorCode()).isEqualTo("EXECUTION_CANCELLED");
        assertThat(String.join("", logs)).doesNotContain("top-secret");
    }

    @Test
    void excludesSetCookieFromResponseHeadersAndLogsOnlyStatus() {
        server.createContext("/headers", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=secret");
            respond(exchange, 200, "text/plain", "ok");
        });
        List<String> logs = new ArrayList<>();
        JsonNode output = executor.execute(
                objectMapper.createObjectNode(),
                context(configuration("GET", "/headers"), logs)
        );

        assertThat(output.path("headers").has("set-cookie")).isFalse();
        assertThat(String.join("", logs)).doesNotContain("session=secret");
        assertThat(String.join("", logs)).contains("statusCode");
    }

    private ObjectNode configuration(String method, String path) {
        return objectMapper.createObjectNode()
                .put("method", method)
                .put("url", "http://127.0.0.1:" + port + path)
                .put("responseFormat", "auto")
                .put("timeoutSeconds", 3)
                .put("followRedirects", false)
                .put("bodyType", "none")
                .set("queryParameters", JsonNodeFactory.instance.arrayNode())
                .deepCopy();
    }

    private ExecutionContext context(ObjectNode config, List<String> logs) {
        return new ExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "http-node",
                config,
                Map.of(),
                Instant.now().plusSeconds(5),
                () -> false,
                (ExecutionLogLevel level, String message, JsonNode details) -> logs.add(message + details)
        );
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
