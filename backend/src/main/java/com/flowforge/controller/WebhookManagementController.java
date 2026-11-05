package com.flowforge.controller;

import com.flowforge.dto.webhook.WebhookRegistrationResponse;
import com.flowforge.dto.webhook.WebhookSecretResponse;
import com.flowforge.service.WebhookRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workflows/{workflowId}/webhooks")
public class WebhookManagementController {
    private final WebhookRegistrationService registrationService;

    public WebhookManagementController(WebhookRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping
    public List<WebhookRegistrationResponse> list(@PathVariable UUID workflowId) {
        return registrationService.listWorkflowWebhooks(workflowId);
    }

    @GetMapping("/{nodeId}")
    public WebhookRegistrationResponse get(@PathVariable UUID workflowId, @PathVariable String nodeId) {
        return registrationService.getWorkflowWebhook(workflowId, nodeId);
    }

    @PostMapping("/{nodeId}/secret")
    public ResponseEntity<WebhookSecretResponse> regenerateSecret(
            @PathVariable UUID workflowId,
            @PathVariable String nodeId
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(registrationService.regenerateSecret(workflowId, nodeId));
    }
}
