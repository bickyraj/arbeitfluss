package com.flowforge.dto.webhook;

import com.flowforge.entity.WebhookRegistration;
import java.time.Instant;
import com.flowforge.model.webhook.WebhookAuthenticationMode;

public record WebhookRegistrationResponse(
        String nodeId,
        String webhookId,
        String httpMethod,
        WebhookAuthenticationMode authenticationMode,
        boolean enabled,
        boolean secretConfigured,
        Instant createdAt,
        Instant updatedAt
) {
    public static WebhookRegistrationResponse fromEntity(WebhookRegistration registration) {
        return new WebhookRegistrationResponse(
                registration.getNodeId(),
                registration.getWebhookId(),
                registration.getHttpMethod(),
                registration.getAuthenticationMode(),
                registration.isEnabled(),
                registration.hasSecret(),
                registration.getCreatedAt(),
                registration.getUpdatedAt()
        );
    }
}
