package com.flowforge.controller;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.dto.execution.ExecutionStartResponse;
import com.flowforge.dto.webhook.WebhookAcceptedResponse;
import com.flowforge.entity.WebhookRegistration;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.exception.WebhookRequestRejectedException;
import com.flowforge.service.WebhookIngressSettings;
import com.flowforge.service.ExecutionService;
import com.flowforge.service.WebhookRateLimiter;
import com.flowforge.service.WebhookRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collections;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_QUERY_BYTES = 8_192;
    private static final int MAX_QUERY_PARAMETERS = 100;
    private static final List<String> SAFE_HEADERS = List.of("content-type", "user-agent", "x-request-id");
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(50)
                    .maxStringLength(262_144)
                    .build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final WebhookRegistrationService registrationService;
    private final WebhookRateLimiter rateLimiter;
    private final ExecutionService executionService;
    private final WebhookIngressSettings ingressSettings;

    public WebhookController(
            WebhookRegistrationService registrationService,
            WebhookRateLimiter rateLimiter,
            ExecutionService executionService,
            WebhookIngressSettings ingressSettings
    ) {
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
        this.executionService = executionService;
        this.ingressSettings = ingressSettings;
    }

    @RequestMapping("/{webhookId}")
    public ResponseEntity<?> receive(
            @PathVariable String webhookId,
            HttpServletRequest request
    ) throws IOException {
        if (!ingressSettings.isEnabled() || !webhookId.matches("[A-Za-z0-9_-]{43}")) {
            return ResponseEntity.notFound().build();
        }
        // Cheap preliminary rejection only. No detached state is trusted to authorize execution.
        WebhookRegistration registration = registrationService.resolveEnabledWebhook(webhookId);
        if (registration == null) return ResponseEntity.notFound().build();
        if (!rateLimiter.allow(webhookId + ":" + request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        var secretHeaders = Collections.list(request.getHeaders("X-FlowForge-Webhook-Secret"));
        if (secretHeaders.size() > 1) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String suppliedSecret = secretHeaders.isEmpty() ? null : secretHeaders.getFirst();
        try {
            registrationService.authorizeIngress(registration, registration.getWorkflow(), request.getMethod(), suppliedSecret);
            // Bounded network reading/parsing happens BEFORE acquiring the workflow lock.
            JsonNode triggerInput = createTriggerInput(request);
            ExecutionStartResponse execution = executionService.startWebhookExecution(
                    webhookId, request.getMethod(), suppliedSecret, triggerInput
            );
            return ResponseEntity.accepted().body(new WebhookAcceptedResponse(
                    true,
                    execution.id(),
                    execution.status()
            ));
        } catch (WebhookRequestRejectedException exception) {
            var response = ResponseEntity.status(exception.status());
            if (exception.allowedMethod() != null) response.header(HttpHeaders.ALLOW, exception.allowedMethod());
            return response.build();
        } catch (PayloadTooLargeException exception) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (InvalidExecutionRequestException | IOException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    private JsonNode createTriggerInput(HttpServletRequest request) throws IOException {
        String queryString = request.getQueryString();
        if (queryString != null && (queryString.length() > MAX_QUERY_BYTES
                || queryString.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES)) {
            throw new PayloadTooLargeException();
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException();
        }
        byte[] bodyBytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bodyBytes.length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException();
        }

        JsonNode body = PAYLOAD_MAPPER.nullNode();
        MediaType contentType = request.getContentType() == null && bodyBytes.length == 0
                ? null : parseContentType(request.getContentType());
        if (bodyBytes.length > 0) {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bodyBytes)).toString();
            if ("application".equals(contentType.getType())) {
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                body = PAYLOAD_MAPPER.readTree(text);
                if (body == null || body.isMissingNode()) {
                    throw new InvalidExecutionRequestException("Invalid webhook JSON body");
                }
            } else {
                body = PAYLOAD_MAPPER.getNodeFactory().textNode(text);
            }
        }

        ObjectNode input = PAYLOAD_MAPPER.createObjectNode();
        input.set("body", body);
        input.set("query", queryParameters(request));
        ObjectNode headers = PAYLOAD_MAPPER.createObjectNode();
        for (String header : SAFE_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && value.length() <= 2_048) {
                headers.put(header, value);
            }
        }
        input.set("headers", headers);
        input.put("method", request.getMethod());
        input.put("receivedAt", Instant.now().toString());
        if (PAYLOAD_MAPPER.writeValueAsBytes(input).length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException();
        }
        return input;
    }

    private MediaType parseContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            throw new InvalidExecutionRequestException("Content-Type is required for a webhook request body");
        }
        try {
            MediaType type = MediaType.parseMediaType(rawContentType);
            boolean json = "application".equals(type.getType())
                    && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"));
            boolean text = "text".equals(type.getType()) && "plain".equals(type.getSubtype());
            if (type.isWildcardType() || type.isWildcardSubtype() || (!json && !text)
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw new IllegalArgumentException("Unsupported webhook content type");
            }
            return type;
        } catch (IllegalArgumentException exception) {
            throw new InvalidExecutionRequestException("Invalid webhook content type");
        }
    }

    private ObjectNode queryParameters(HttpServletRequest request) {
        ObjectNode query = PAYLOAD_MAPPER.createObjectNode();
        Map<String, String[]> parameters;
        try {
            parameters = request.getParameterMap();
        } catch (IllegalArgumentException exception) {
            throw new InvalidExecutionRequestException("Invalid webhook query parameters");
        }
        if (parameters.size() > MAX_QUERY_PARAMETERS) {
            throw new PayloadTooLargeException();
        }
        parameters.forEach((name, values) -> {
            if (name.length() > 256 || values.length > 20) {
                throw new PayloadTooLargeException();
            }
            ArrayNode array = query.putArray(name);
            for (String value : values) {
                if (value.length() > 2_048) {
                    throw new PayloadTooLargeException();
                }
                array.add(value);
            }
        });
        return query;
    }

    private static final class PayloadTooLargeException extends RuntimeException {
    }
}
