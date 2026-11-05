package com.flowforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.dto.webhook.WebhookRegistrationResponse;
import com.flowforge.dto.webhook.WebhookSecretResponse;
import com.flowforge.entity.WebhookRegistration;
import com.flowforge.entity.Workflow;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.exception.ResourceNotFoundException;
import com.flowforge.exception.WebhookRequestRejectedException;
import org.springframework.http.HttpStatus;
import com.flowforge.model.webhook.WebhookAuthenticationMode;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.repository.WorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class WebhookRegistrationService {
    private static final Set<String> ALLOWED_METHODS = Set.of("POST", "GET", "PUT", "PATCH", "DELETE");
    private final WebhookRegistrationRepository registrationRepository;
    private final WorkflowRepository workflowRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebhookRegistrationService(
            WebhookRegistrationRepository registrationRepository,
            WorkflowRepository workflowRepository
    ) {
        this.registrationRepository = registrationRepository;
        this.workflowRepository = workflowRepository;
    }

    @Transactional
    public void synchronizeWorkflow(Workflow workflow) {
        reconcileWorkflow(workflow);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void synchronizeSavedWorkflow(UUID workflowId) {
        // A fresh persistence context and the shared parent lock prevent stale startup snapshots.
        // A workflow deleted since ID discovery needs no reconciliation.
        workflowRepository.findByIdForRegistrationUpdate(workflowId).ifPresent(this::reconcileWorkflow);
    }

    private void reconcileWorkflow(Workflow workflow) {
        workflow.setDefinition(sanitizeDefinition(workflow.getDefinition()));
        JsonNode nodes = workflow.getDefinition() == null ? null : workflow.getDefinition().get("nodes");
        List<WebhookNode> webhookNodes = parseWebhookNodes(nodes);
        List<WebhookRegistration> current = registrationRepository.findAllByWorkflow_Id(workflow.getId());
        Set<String> retainedNodeIds = new HashSet<>();

        for (WebhookNode webhookNode : webhookNodes) {
            retainedNodeIds.add(webhookNode.nodeId());
            WebhookRegistration registration = registrationRepository
                    .findByWorkflow_IdAndNodeId(workflow.getId(), webhookNode.nodeId())
                    .orElseGet(() -> new WebhookRegistration(
                            workflow,
                            webhookNode.nodeId(),
                            generateWebhookId(),
                            webhookNode.method(),
                            webhookNode.authenticationMode()
                    ));
            boolean hasRequiredSecret = webhookNode.authenticationMode() == WebhookAuthenticationMode.NONE
                    || registration.hasSecret();
            registration.update(
                    webhookNode.method(),
                    webhookNode.authenticationMode(),
                    workflow.isActive() && webhookNode.active() && hasRequiredSecret
            );
            registrationRepository.save(registration);
        }

        current.stream()
                .filter(registration -> !retainedNodeIds.contains(registration.getNodeId()))
                .forEach(registrationRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<WebhookRegistrationResponse> listWorkflowWebhooks(UUID workflowId) {
        if (!workflowRepository.existsById(workflowId)) {
            throw new ResourceNotFoundException("Workflow not found: " + workflowId);
        }
        return registrationRepository.findAllByWorkflow_Id(workflowId).stream()
                .map(WebhookRegistrationResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public WebhookRegistrationResponse getWorkflowWebhook(UUID workflowId, String nodeId) {
        return WebhookRegistrationResponse.fromEntity(registrationRepository
                .findByWorkflow_IdAndNodeId(workflowId, nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook registration not found")));
    }

    @Transactional
    public WebhookSecretResponse regenerateSecret(UUID workflowId, String nodeId) {
        // Serialize rotation with workflow updates/deletion before reading registration state.
        workflowRepository.findByIdForRegistrationUpdate(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found"));
        WebhookRegistration registration = registrationRepository
                .findByWorkflow_IdAndNodeId(workflowId, nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook registration not found"));
        if (registration.getAuthenticationMode() != WebhookAuthenticationMode.SECRET_TOKEN) {
            throw new InvalidExecutionRequestException("Secret authentication is not enabled for this webhook");
        }
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        registration.setSecretHash(hashSecret(secret));
        registration.update(
                registration.getHttpMethod(),
                registration.getAuthenticationMode(),
                registration.getWorkflow().isActive() && isNodeActive(registration.getWorkflow(), nodeId)
        );
        registrationRepository.flush();
        return new WebhookSecretResponse(secret, WebhookRegistrationResponse.fromEntity(registration));
    }

    @Transactional(readOnly = true)
    public WebhookRegistration resolveEnabledWebhook(String webhookId) {
        WebhookRegistration registration = registrationRepository.findByWebhookId(webhookId).orElse(null);
        if (registration == null || !registration.isEnabled() || !registration.getWorkflow().isActive()) {
            return null;
        }
        return registration;
    }

    public void authorizeIngress(WebhookRegistration registration, Workflow workflow, String method, String presentedSecret) {
        if (registration == null || !registration.isEnabled() || !workflow.isActive()
                || !registration.getWorkflowId().equals(workflow.getId())
                || (registration.getAuthenticationMode() == WebhookAuthenticationMode.SECRET_TOKEN && !registration.hasSecret())) {
            throw new WebhookRequestRejectedException(HttpStatus.NOT_FOUND);
        }
        List<WebhookNode> nodes;
        try {
            nodes = parseWebhookNodes(workflow.getDefinition() == null ? null : workflow.getDefinition().get("nodes"));
        } catch (InvalidExecutionRequestException exception) {
            throw new WebhookRequestRejectedException(HttpStatus.NOT_FOUND);
        }
        WebhookNode node = nodes.stream().filter(candidate -> candidate.nodeId().equals(registration.getNodeId()))
                .findFirst().orElseThrow(() -> new WebhookRequestRejectedException(HttpStatus.NOT_FOUND));
        if (!node.active() || !node.method().equals(registration.getHttpMethod())
                || node.authenticationMode() != registration.getAuthenticationMode()) {
            throw new WebhookRequestRejectedException(HttpStatus.NOT_FOUND);
        }
        if (!registration.getHttpMethod().equals(method)) {
            throw new WebhookRequestRejectedException(HttpStatus.METHOD_NOT_ALLOWED, registration.getHttpMethod());
        }
        if (!authenticate(registration, presentedSecret)) {
            throw new WebhookRequestRejectedException(HttpStatus.UNAUTHORIZED);
        }
    }

    public boolean authenticate(WebhookRegistration registration, String presentedSecret) {
        if (registration.getAuthenticationMode() == WebhookAuthenticationMode.NONE) {
            return true;
        }
        if (presentedSecret == null || presentedSecret.length() > 128 || !registration.hasSecret()) {
            return false;
        }
        byte[] expected = registration.getSecretHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hashSecret(presentedSecret).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    @Transactional
    public void deleteWorkflowWebhooks(UUID workflowId) {
        registrationRepository.deleteAllByWorkflow_Id(workflowId);
    }

    public JsonNode sanitizeDefinition(JsonNode definition) {
        if (definition == null) return null;
        if (!definition.isObject()) {
            throw new InvalidExecutionRequestException("Workflow definition must be an object");
        }
        JsonNode nodes = definition.get("nodes");
        // Validate before persistence; do not invoke or alter execution graph validation.
        parseWebhookNodes(nodes);
        ObjectNode safeDefinition = (ObjectNode) definition.deepCopy();
        if (nodes != null) {
            for (JsonNode node : safeDefinition.path("nodes")) {
                if (!"webhook-trigger".equals(node.path("type").asText())) continue;
                ObjectNode config = (ObjectNode) node.get("configuration");
                if (config != null) {
                    config.retain(List.of("method", "authentication", "active", "responseMode"));
                }
            }
        }
        return safeDefinition;
    }

    private List<WebhookNode> parseWebhookNodes(JsonNode nodes) {
        List<WebhookNode> found = new ArrayList<>();
        if (nodes == null) return found;
        if (!nodes.isArray()) {
            throw new InvalidExecutionRequestException("Workflow nodes must be an array");
        }
        Set<String> nodeIds = new HashSet<>();
        Set<String> duplicateNodeIds = new HashSet<>();
        for (JsonNode node : nodes) {
            if (node.path("id").isTextual() && !nodeIds.add(node.path("id").asText())) {
                duplicateNodeIds.add(node.path("id").asText());
            }
        }
        for (JsonNode node : nodes) {
            if (!node.isObject() || !"webhook-trigger".equals(node.path("type").asText())) {
                continue;
            }
            String nodeId = node.path("id").asText("");
            if (!node.path("id").isTextual() || !nodeId.matches("[A-Za-z0-9_-]{1,255}")) {
                throw new InvalidExecutionRequestException(
                        "Webhook Trigger node ID must contain 1 to 255 letters, digits, underscores or hyphens"
                );
            }
            if (duplicateNodeIds.contains(nodeId)) {
                throw new InvalidExecutionRequestException("Webhook Trigger node IDs must be unique");
            }
            JsonNode config = node.path("configuration");
            if (!config.isMissingNode() && !config.isObject()) {
                throw new InvalidExecutionRequestException("Webhook Trigger configuration must be an object");
            }
            if ((config.has("method") && !config.path("method").isTextual())
                    || (config.has("authentication") && !config.path("authentication").isTextual())
                    || (config.has("active") && !config.path("active").isBoolean())) {
                throw new InvalidExecutionRequestException("Webhook Trigger configuration has invalid value types");
            }
            String method = config.path("method").asText("POST").toUpperCase(Locale.ROOT);
            if (!ALLOWED_METHODS.contains(method)) {
                throw new InvalidExecutionRequestException("Webhook Trigger has an unsupported HTTP method");
            }
            String authentication = config.path("authentication").asText("secret-token");
            WebhookAuthenticationMode authenticationMode = switch (authentication) {
                case "none" -> WebhookAuthenticationMode.NONE;
                case "secret-token" -> WebhookAuthenticationMode.SECRET_TOKEN;
                default -> throw new InvalidExecutionRequestException("Webhook Trigger has an unsupported authentication mode");
            };
            boolean active = !config.has("active") || config.path("active").asBoolean();
            found.add(new WebhookNode(nodeId, method, authenticationMode, active));
        }
        return found;
    }

    private boolean isNodeActive(Workflow workflow, String nodeId) {
        JsonNode nodes = workflow.getDefinition() == null ? null : workflow.getDefinition().get("nodes");
        if (nodes == null || !nodes.isArray()) {
            return false;
        }
        for (JsonNode node : nodes) {
            if (nodeId.equals(node.path("id").asText())) {
                return !node.path("configuration").has("active")
                        || node.path("configuration").path("active").asBoolean();
            }
        }
        return false;
    }

    private String generateWebhookId() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashSecret(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record WebhookNode(
            String nodeId,
            String method,
            WebhookAuthenticationMode authenticationMode,
            boolean active
    ) {
    }
}
