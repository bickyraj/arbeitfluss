package com.flowforge.entity;

import com.flowforge.model.webhook.WebhookAuthenticationMode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "webhook_registrations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_registration_workflow_node",
                columnNames = {"workflow_id", "node_id"}
        )
)
public class WebhookRegistration {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "webhook_id", nullable = false, unique = true, length = 64)
    private String webhookId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_mode", nullable = false, length = 20)
    private WebhookAuthenticationMode authenticationMode;

    @Column(name = "secret_hash", length = 64)
    private String secretHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookRegistration() {
    }

    public WebhookRegistration(
            Workflow workflow,
            String nodeId,
            String webhookId,
            String httpMethod,
            WebhookAuthenticationMode authenticationMode
    ) {
        this.workflow = workflow;
        this.nodeId = nodeId;
        this.webhookId = webhookId;
        this.httpMethod = httpMethod;
        this.authenticationMode = authenticationMode;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void update(String httpMethod, WebhookAuthenticationMode authenticationMode, boolean enabled) {
        this.httpMethod = httpMethod;
        this.authenticationMode = authenticationMode;
        this.enabled = enabled;
        if (authenticationMode == WebhookAuthenticationMode.NONE) {
            this.secretHash = null;
        }
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public UUID getId() { return id; }
    public String getWebhookId() { return webhookId; }
    public Workflow getWorkflow() { return workflow; }
    public UUID getWorkflowId() { return workflow.getId(); }
    public String getNodeId() { return nodeId; }
    public String getHttpMethod() { return httpMethod; }
    public WebhookAuthenticationMode getAuthenticationMode() { return authenticationMode; }
    public String getSecretHash() { return secretHash; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean hasSecret() { return secretHash != null; }
}
