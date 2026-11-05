package com.flowforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.dto.webhook.WebhookRegistrationResponse;
import com.flowforge.entity.WebhookRegistration;
import com.flowforge.entity.Workflow;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.exception.ResourceNotFoundException;
import com.flowforge.model.webhook.WebhookAuthenticationMode;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.repository.WorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        JsonNode definition = workflow.getDefinition();
        if (definition != null && definition.isObject() && definition.path("nodes").isArray()) {
            ObjectNode safeDefinition = (ObjectNode) definition.deepCopy();
            for (JsonNode node : safeDefinition.path("nodes")) {
                if (!node.isObject() || !"webhook-trigger".equals(node.path("type").asText())) continue;
                JsonNode configuration = node.get("configuration");
                if (configuration instanceof ObjectNode config) {
                    config.remove(List.of("secret", "secretToken", "secretHash", "webhookId", "url"));
                }
            }
            workflow.setDefinition(safeDefinition);
        }
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

    @Transactional
    public String regenerateSecret(UUID workflowId, String nodeId) {
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
        return secret;
    }

    @Transactional(readOnly = true)
    public WebhookRegistration resolveEnabledWebhook(String webhookId) {
        WebhookRegistration registration = registrationRepository.findByWebhookId(webhookId).orElse(null);
        if (registration == null || !registration.isEnabled() || !registration.getWorkflow().isActive()) {
            return null;
        }
        return registration;
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

    public void deleteWorkflowWebhooks(UUID workflowId) {
        registrationRepository.deleteAllByWorkflow_Id(workflowId);
    }

    private List<WebhookNode> parseWebhookNodes(JsonNode nodes) {
        List<WebhookNode> found = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return found;
        }
        for (JsonNode node : nodes) {
            if (!node.isObject() || !"webhook-trigger".equals(node.path("type").asText())) {
                continue;
            }
            String nodeId = node.path("id").asText("");
            if (nodeId.isBlank()) {
                throw new InvalidExecutionRequestException("Webhook Trigger must have a node ID");
            }
            JsonNode config = node.path("configuration");
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
