package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.runtime.ExecutionContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntityContainer;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class HttpRequestExecutor implements NodeExecutor, AutoCloseable {
    private static final long MAX_RESPONSE_BYTES = 5L * 1024 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADER_COUNT = 100;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> RESPONSE_FORMATS = Set.of("auto", "json", "text");
    private static final Set<String> BODY_TYPES = Set.of("none", "json");
    private static final Set<String> SAFE_RESPONSE_HEADERS = Set.of(
            "content-type", "content-length", "content-language", "cache-control",
            "etag", "last-modified", "date", "vary"
    );
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?i)(authorization|cookie|api.?key|access.?token|auth.?token|client.?secret|password)"
    );

    private final ObjectMapper objectMapper;
    private final OutboundAddressPolicy addressPolicy;
    private final CloseableHttpClient httpClient;

    @Autowired
    public HttpRequestExecutor(ObjectMapper objectMapper) {
        this(objectMapper, new OutboundAddressPolicy());
    }

    HttpRequestExecutor(ObjectMapper objectMapper, OutboundAddressPolicy addressPolicy) {
        this.objectMapper = objectMapper;
        this.addressPolicy = addressPolicy;
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return addressPolicy.resolvePublicAddresses(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder
                .create()
                .setDnsResolver(resolver)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }

    @Override
    public String nodeType() {
        return "http-request";
    }

    @Override
    public boolean abortOnWorkflowCancellation() {
        return true;
    }

    @Override
    public void validateConfiguration(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            throw invalidConfiguration("Configuration must be an object.");
        }
        String method = text(configuration, "method", "GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw invalidConfiguration("Method must be GET, POST, PUT, PATCH, or DELETE.");
        }
        String url = text(configuration, "url", "");
        if (url.length() > 8192) {
            throw invalidConfiguration("URL cannot exceed 8192 characters.");
        }
        validateUrlSyntax(url);
        String format = text(configuration, "responseFormat", "auto").toLowerCase(Locale.ROOT);
        if (!RESPONSE_FORMATS.contains(format)) {
            throw invalidConfiguration("Response format must be Auto, JSON, or Text.");
        }
        int timeout = integer(configuration, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS) {
            throw invalidConfiguration("Request timeout must be between 1 and 3600 seconds.");
        }
        if (configuration.has("followRedirects") && !configuration.get("followRedirects").isBoolean()) {
            throw invalidConfiguration("Follow redirects must be true or false.");
        }
        validateEntries(configuration.path("queryParameters"), "query parameter", false);
        validateEntries(configuration.path("headers"), "header", true);
        String bodyType = text(configuration, "bodyType", "none").toLowerCase(Locale.ROOT);
        if (!BODY_TYPES.contains(bodyType)) {
            throw invalidConfiguration("Body type must be None or JSON.");
        }
        if ("json".equals(bodyType)) {
            if (!Set.of("POST", "PUT", "PATCH").contains(method)) {
                throw invalidConfiguration("JSON request bodies are supported only for POST, PUT, and PATCH.");
            }
            JsonNode body = configuration.get("body");
            if (body == null || !(body.isObject() || body.isArray() || body.isNull())) {
                throw invalidConfiguration("JSON body must be an object, array, or null.");
            }
        }
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        JsonNode configuration = context.nodeConfiguration();
        validateConfiguration(configuration);
        String method = text(configuration, "method", "GET").toUpperCase(Locale.ROOT);
        URI uri = buildUri(configuration);
        boolean followRedirects = configuration.path("followRedirects").asBoolean(false);
        int timeoutSeconds = integer(configuration, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        boolean includeSensitiveHeaders = true;
        try {
            for (int redirectCount = 0; ; redirectCount++) {
                ensureActive(context);
                validateDestination(uri);
                long remaining = context.remainingTimeMillis();
                if (remaining <= 0) {
                    throw new NodeExecutionException("HTTP request exceeded the workflow execution deadline.", "EXECUTION_TIMEOUT");
                }
                long timeoutMillis = Math.min(TimeUnit.SECONDS.toMillis(timeoutSeconds), remaining);
                HttpUriRequestBase request = createRequest(method, uri, configuration, timeoutMillis, includeSensitiveHeaders);
                try (AutoCloseable ignored = context.onCancellation(request::cancel);
                     var response = httpClient.execute(request)) {
                    int status = response.getCode();
                    String location = response.getFirstHeader(HttpHeaders.LOCATION) == null
                            ? null
                            : response.getFirstHeader(HttpHeaders.LOCATION).getValue();
                    if (followRedirects && isRedirect(status) && location != null) {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw new NodeExecutionException("HTTP request exceeded the redirect limit.", "HTTP_REDIRECT_LIMIT");
                        }
                        URI next = uri.resolve(location);
                        validateUrl(next);
                        if ("https".equalsIgnoreCase(uri.getScheme())
                                && "http".equalsIgnoreCase(next.getScheme())) {
                            throw new NodeExecutionException("HTTPS redirects to HTTP are not allowed.", "SSRF_BLOCKED");
                        }
                        if (!sameHost(uri, next)) includeSensitiveHeaders = false;
                        uri = next;
                        if (status == 303 || ((status == 301 || status == 302) && !method.equals("GET"))) {
                            method = "GET";
                        }
                        continue;
                    }

                    byte[] bodyBytes = readBounded(response.getEntity() == null
                            ? InputStream.nullInputStream()
                            : response.getEntity().getContent());
                    ObjectNode output = JsonNodeFactory.instance.objectNode();
                    output.put("statusCode", status);
                    output.set("headers", safeHeaders(response));
                    output.put("responseFormat", detectedResponseFormat(response, configuration));
                    try {
                        output.set("body", parseBody(bodyBytes, response, configuration));
                    } catch (NodeExecutionException exception) {
                        output.put("body", new String(bodyBytes, StandardCharsets.UTF_8));
                        output.put("success", false);
                        throw new NodeExecutionException(exception.getMessage(), exception.errorCode(), output);
                    }
                    output.put("success", status >= 200 && status < 300);
                    if (status < 200 || status >= 300) {
                        throw new NodeExecutionException(
                                "HTTP request returned status " + status + ".",
                                "HTTP_STATUS_ERROR",
                                output
                        );
                    }
                    ObjectNode details = JsonNodeFactory.instance.objectNode().put("statusCode", status);
                    context.log(ExecutionLogLevel.INFO, "HTTP request completed.", details);
                    return output;
                }
            }
        } catch (NodeExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            if (context.isCancellationRequested()) {
                throw new NodeExecutionException("HTTP request cancelled.", "EXECUTION_CANCELLED");
            }
            if (context.isExpired()) {
                throw new NodeExecutionException("HTTP request exceeded the workflow execution deadline.", "EXECUTION_TIMEOUT");
            }
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new NodeExecutionException("HTTP request was interrupted.", "EXECUTION_CANCELLED");
            }
            throw new NodeExecutionException(safeFailureMessage(exception), safeErrorCode(exception));
        }
    }

    public void close() throws IOException {
        httpClient.close();
    }

    private HttpUriRequestBase createRequest(
            String method,
            URI uri,
            JsonNode configuration,
            long timeoutMillis,
            boolean includeSensitiveHeaders
    ) {
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        Timeout timeout = Timeout.ofMilliseconds(Math.max(1, timeoutMillis));
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setConnectTimeout(timeout)
                .setResponseTimeout(timeout)
                .build());
        JsonNode headers = configuration.path("headers");
        if (headers.isArray()) {
            headers.forEach(header -> {
                if (header.path("enabled").asBoolean(true)) {
                    String name = header.path("name").asText();
                    if (includeSensitiveHeaders || !isSensitiveHeader(name)) {
                        request.addHeader(name, header.path("value").asText());
                    }
                }
            });
        }
        if ("json".equalsIgnoreCase(text(configuration, "bodyType", "none"))
                && Set.of("POST", "PUT", "PATCH").contains(method)) {
            if (!request.containsHeader(HttpHeaders.CONTENT_TYPE)) {
                request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
            }
            JsonNode body = configuration.get("body");
            String json = body == null || body.isNull() ? "null" : body.toString();
            ((HttpEntityContainer) request).setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        }
        return request;
    }

    private URI buildUri(JsonNode configuration) {
        try {
            URIBuilder builder = new URIBuilder(text(configuration, "url", ""));
            JsonNode parameters = configuration.path("queryParameters");
            if (parameters.isArray()) {
                parameters.forEach(parameter -> {
                    if (parameter.path("enabled").asBoolean(true)) {
                        builder.addParameter(parameter.path("name").asText(), parameter.path("value").asText());
                    }
                });
            }
            URI uri = builder.build();
            validateUrl(uri);
            return uri;
        } catch (NodeExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidConfiguration("URL is malformed.");
        }
    }

    private void validateUrlSyntax(String url) {
        try {
            validateUrl(URI.create(url));
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration("URL is malformed.");
        }
    }

    private void validateUrl(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new NodeExecutionException("Only HTTP and HTTPS destinations are allowed.", "SSRF_BLOCKED");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            throw new NodeExecutionException("URL must contain a valid host and cannot include credentials.", "INVALID_URL");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw new NodeExecutionException("URL contains an invalid port.", "INVALID_URL");
        }
    }

    private void validateDestination(URI uri) {
        validateUrl(uri);
        try {
            addressPolicy.resolvePublicAddresses(normalizeHost(uri.getHost()));
        } catch (SSRFBlockedException exception) {
            throw new NodeExecutionException("Destination is not publicly routable.", "SSRF_BLOCKED");
        } catch (UnknownHostException exception) {
            throw new NodeExecutionException("Unable to resolve the HTTP destination.", "HTTP_DNS_FAILURE");
        }
    }

    private JsonNode parseBody(byte[] bytes, HttpResponse response, JsonNode configuration) {
        String responseText = new String(bytes, StandardCharsets.UTF_8);
        String format = text(configuration, "responseFormat", "auto").toLowerCase(Locale.ROOT);
        String contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE) == null
                ? ""
                : response.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue().toLowerCase(Locale.ROOT);
        boolean json = format.equals("json")
                || (format.equals("auto") && (contentType.contains("application/json") || contentType.contains("+json")));
        if (!json) {
            return JsonNodeFactory.instance.textNode(responseText);
        }

        try {
            return objectMapper.readTree(bytes);
        } catch (IOException exception) {
            if (format.equals("json")) {
                throw new NodeExecutionException("HTTP response was not valid JSON.", "INVALID_RESPONSE_FORMAT");
            }
            return JsonNodeFactory.instance.textNode(responseText);
        }
    }

    private String detectedResponseFormat(HttpResponse response, JsonNode configuration) {
        String format = text(configuration, "responseFormat", "auto").toLowerCase(Locale.ROOT);
        if (format.equals("json")) return "JSON";
        if (format.equals("text")) return "Text";
        String contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE) == null
                ? ""
                : response.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue().toLowerCase(Locale.ROOT);
        return contentType.contains("application/json") || contentType.contains("+json") ? "JSON" : "Text";
    }

    private ObjectNode safeHeaders(HttpResponse response) {
        ObjectNode safe = JsonNodeFactory.instance.objectNode();
        for (var header : response.getHeaders()) {
            String name = header.getName().toLowerCase(Locale.ROOT);
            if (SAFE_RESPONSE_HEADERS.contains(name) && safe.size() < MAX_HEADER_COUNT) {
                safe.put(name, header.getValue().substring(0, Math.min(4096, header.getValue().length())));
            }
        }
        return safe;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, MAX_RESPONSE_BYTES + 1 - total))) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new NodeExecutionException("HTTP response exceeded the 5 MB size limit.", "RESPONSE_TOO_LARGE");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void validateEntries(JsonNode entries, String label, boolean headers) {
        if (entries.isMissingNode() || entries.isNull()) return;
        if (!entries.isArray()) {
            throw invalidConfiguration("Configured " + label + "s must be an array.");
        }
        if (entries.size() > MAX_HEADER_COUNT) {
            throw invalidConfiguration("At most 100 " + label + "s may be configured.");
        }
        for (JsonNode entry : entries) {
            String name = entry.path("name").asText("");
            JsonNode value = entry.get("value");
            if (!entry.isObject() || name.isBlank() || value == null || !value.isTextual()) {
                throw invalidConfiguration("Each " + label + " requires a name and string value.");
            }
            if (value.textValue().length() > 8192
                    || (headers && (value.textValue().contains("\r") || value.textValue().contains("\n")))) {
                throw invalidConfiguration("The " + label + " value is too long or contains invalid line breaks.");
            }
            if (entry.has("enabled") && !entry.get("enabled").isBoolean()) {
                throw invalidConfiguration("The enabled state for each " + label + " must be boolean.");
            }
            if (headers && (!HEADER_NAME.matcher(name).matches()
                    || Set.of("host", "content-length", "transfer-encoding", "connection").contains(name.toLowerCase(Locale.ROOT)))) {
                throw invalidConfiguration("Header name is invalid or reserved.");
            }
        }
    }

    private void ensureActive(ExecutionContext context) {
        if (context.isCancellationRequested()) {
            throw new NodeExecutionException("HTTP request cancelled.", "EXECUTION_CANCELLED");
        }
        if (context.isExpired()) {
            throw new NodeExecutionException("HTTP request exceeded the workflow execution deadline.", "EXECUTION_TIMEOUT");
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private boolean sameHost(URI first, URI second) {
        return normalizeHost(first.getHost()).equalsIgnoreCase(normalizeHost(second.getHost()))
                && first.getPort() == second.getPort()
                && first.getScheme().equalsIgnoreCase(second.getScheme());
    }

    private String normalizeHost(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private boolean isSensitiveHeader(String header) {
        return SENSITIVE_HEADER.matcher(header).find()
                || Set.of("proxy-authorization", "proxy-authenticate")
                .contains(header.toLowerCase(Locale.ROOT));
    }

    private int integer(JsonNode configuration, String key, int fallback) {
        JsonNode value = configuration.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidConfiguration("Request timeout must be an integer.");
        }
        return value.intValue();
    }

    private String text(JsonNode configuration, String key, String fallback) {
        JsonNode value = configuration.get(key);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw invalidConfiguration(key + " must be text.");
        return value.textValue();
    }

    private NodeExecutionException invalidConfiguration(String message) {
        return new NodeExecutionException(message, "INVALID_NODE_CONFIGURATION");
    }

    private String safeErrorCode(Exception exception) {
        if (hasCause(exception, SSRFBlockedException.class)) return "SSRF_BLOCKED";
        if (hasCause(exception, UnknownHostException.class)) return "HTTP_DNS_FAILURE";
        if (exception instanceof SSLException) return "HTTP_TLS_FAILURE";
        if (exception instanceof java.net.SocketTimeoutException) return "HTTP_TIMEOUT";
        if (exception instanceof IOException) return "HTTP_CONNECTION_FAILURE";
        return "HTTP_REQUEST_FAILED";
    }

    private String safeFailureMessage(Exception exception) {
        if (hasCause(exception, SSRFBlockedException.class)) return "Destination is not publicly routable.";
        if (hasCause(exception, UnknownHostException.class)) return "Unable to resolve the HTTP destination.";
        if (hasCause(exception, SSLException.class)) return "TLS certificate validation failed.";
        if (hasCause(exception, java.net.SocketTimeoutException.class)) return "HTTP request timed out.";
        if (hasCause(exception, IOException.class)) return "Unable to connect to the HTTP destination.";
        return "HTTP request failed.";
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }
}
