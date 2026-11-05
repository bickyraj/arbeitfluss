package com.flowforge.exception;

import org.springframework.http.HttpStatus;

public class WebhookRequestRejectedException extends RuntimeException {
    private final HttpStatus status;
    private final String allowedMethod;

    public WebhookRequestRejectedException(HttpStatus status) {
        this(status, null);
    }

    public WebhookRequestRejectedException(HttpStatus status, String allowedMethod) {
        super("Webhook request rejected");
        this.status = status;
        this.allowedMethod = allowedMethod;
    }

    public HttpStatus status() { return status; }
    public String allowedMethod() { return allowedMethod; }
}
