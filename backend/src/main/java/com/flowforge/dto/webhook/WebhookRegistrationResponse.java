package com.flowforge.dto.webhook;

import com.flowforge.entity.WebhookRegistration;
import com.flowforge.model.webhook.WebhookAuthenticationMode;

public record WebhookRegistrationResponse(
        String nodeId,
        String webhookId,
        String httpMethod,
        WebhookAuthenticationMode authenticationMode,
        boolean enabled,
        boolean secretConfigured
) {
    public static WebhookRegistrationResponse fromEntity(WebhookRegistration registration) {
        return new WebhookRegistrationResponse(
                registration.getNodeId(),
                registration.getWebhookId(),
                registration.getHttpMethod(),
                registration.getAuthenticationMode(),
                registration.isEnabled(),
                registration.hasSecret()
        );
    }
}
