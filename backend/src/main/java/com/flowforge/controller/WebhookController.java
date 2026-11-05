package com.flowforge.controller;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.dto.execution.ExecutionStartResponse;
import com.flowforge.dto.webhook.WebhookAcceptedResponse;
import com.flowforge.entity.WebhookRegistration;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.model.webhook.WebhookAuthenticationMode;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_QUERY_BYTES = 8_192;
    private static final int MAX_QUERY_PARAMETERS = 100;
    private static final List<String> SAFE_HEADERS = List.of("content-type", "user-agent", "x-request-id");
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(50)
                    .maxStringLength(262_144)
                    .build())
            .build());

    private final WebhookRegistrationService registrationService;
    private final WebhookRateLimiter rateLimiter;
    private final ExecutionService executionService;

    public WebhookController(
            WebhookRegistrationService registrationService,
            WebhookRateLimiter rateLimiter,
            ExecutionService executionService
    ) {
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
        this.executionService = executionService;
    }

    @RequestMapping("/{webhookId}")
    public ResponseEntity<?> receive(
            @PathVariable String webhookId,
            HttpServletRequest request
    ) throws IOException {
        WebhookRegistration registration = registrationService.resolveEnabledWebhook(webhookId);
        if (registration == null) {
            return ResponseEntity.notFound().build();
        }
        if (!rateLimiter.allow(webhookId + ":" + request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (!registration.getHttpMethod().equals(request.getMethod())) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, registration.getHttpMethod())
                    .build();
        }

        String suppliedSecret = request.getHeader("X-FlowForge-Webhook-Secret");
        if (registration.getAuthenticationMode() == WebhookAuthenticationMode.SECRET_TOKEN
                && !registrationService.authenticate(registration, suppliedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            JsonNode triggerInput = createTriggerInput(request);
            ExecutionStartResponse execution = executionService.startWebhookExecution(
                    registration.getWorkflowId(),
                    triggerInput
            );
            return ResponseEntity.accepted().body(new WebhookAcceptedResponse(
                    true,
                    execution.id(),
                    execution.status()
            ));
        } catch (PayloadTooLargeException exception) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (InvalidExecutionRequestException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    private JsonNode createTriggerInput(HttpServletRequest request) throws IOException {
        String queryString = request.getQueryString();
        if (queryString != null && queryString.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
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
        if (bodyBytes.length > 0) {
            MediaType contentType = parseContentType(request.getContentType());
            if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                    || (contentType.getSubtype() != null && contentType.getSubtype().endsWith("+json"))) {
                try {
                    body = PAYLOAD_MAPPER.readTree(bodyBytes);
                } catch (IOException exception) {
                    throw new InvalidExecutionRequestException("Webhook request body is not valid JSON");
                }
                if (body == null) {
                    throw new InvalidExecutionRequestException("Webhook request body is empty");
                }
            } else if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
                body = PAYLOAD_MAPPER.getNodeFactory().textNode(new String(bodyBytes, StandardCharsets.UTF_8));
            } else {
                throw new InvalidExecutionRequestException("Unsupported webhook content type");
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
            return MediaType.parseMediaType(rawContentType);
        } catch (IllegalArgumentException exception) {
            throw new InvalidExecutionRequestException("Invalid webhook content type");
        }
    }

    private ObjectNode queryParameters(HttpServletRequest request) {
        ObjectNode query = PAYLOAD_MAPPER.createObjectNode();
        Map<String, String[]> parameters = request.getParameterMap();
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
