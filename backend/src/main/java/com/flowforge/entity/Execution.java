package com.flowforge.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.model.execution.ExecutionTriggerType;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.entity.Workflow;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "executions")
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(name = "workflow_name", nullable = false)
    private String workflowName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workflow_definition", nullable = false, columnDefinition = "jsonb")
    private JsonNode workflowDefinition;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    private ExecutionTriggerType triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_input", nullable = false, columnDefinition = "jsonb")
    private JsonNode triggerInput;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Execution() {
    }

    public Execution(Workflow workflow, String workflowName, JsonNode workflowDefinition,
                             ExecutionTriggerType triggerType, JsonNode triggerInput) {
        this.workflow = workflow;
        this.workflowName = workflowName;
        this.workflowDefinition = workflowDefinition.deepCopy();
        this.triggerType = triggerType;
        this.triggerInput = triggerInput.deepCopy();
        this.status = ExecutionStatus.QUEUED;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public void transitionTo(ExecutionStatus nextStatus, String error) {
        boolean valid = switch (status) {
            case QUEUED -> nextStatus == ExecutionStatus.RUNNING
                    || nextStatus == ExecutionStatus.FAILED
                    || nextStatus == ExecutionStatus.CANCELLED;
            case RUNNING -> nextStatus == ExecutionStatus.SUCCEEDED
                    || nextStatus == ExecutionStatus.FAILED
                    || nextStatus == ExecutionStatus.CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Invalid workflow execution status transition: " + status + " -> " + nextStatus);
        }

        Instant now = Instant.now();
        if (nextStatus == ExecutionStatus.RUNNING) {
            startedAt = now;
        }
        if (nextStatus == ExecutionStatus.SUCCEEDED
                || nextStatus == ExecutionStatus.FAILED
                || nextStatus == ExecutionStatus.CANCELLED) {
            if (startedAt == null) {
                startedAt = createdAt;
            }
            completedAt = now;
            durationMs = Duration.between(startedAt, completedAt).toMillis();
            errorMessage = error;
        }
        status = nextStatus;
    }

    public UUID getId() { return id; }
    public Workflow getWorkflow() { return workflow; }
    public UUID getWorkflowId() { return workflow.getId(); }
    public String getWorkflowName() { return workflowName; }
    public JsonNode getWorkflowDefinition() { return workflowDefinition.deepCopy(); }
    public ExecutionTriggerType getTriggerType() { return triggerType; }
    public JsonNode getTriggerInput() { return triggerInput.deepCopy(); }
    public ExecutionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
