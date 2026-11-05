package com.flowforge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookIngressSettings {
    private final boolean enabled;

    public WebhookIngressSettings(@Value("${flowforge.webhooks.ingress-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
