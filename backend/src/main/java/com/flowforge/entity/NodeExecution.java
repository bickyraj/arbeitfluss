package com.flowforge.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.model.execution.NodeExecutionStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "node_executions",
        uniqueConstraints = @UniqueConstraint(name = "uq_node_execution_id", columnNames = {"execution_id", "workflow_node_id"}))
public class NodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @Column(name = "workflow_node_id", nullable = false)
    private String workflowNodeId;

    @Column(name = "node_type", nullable = false, length = 100)
    private String nodeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeExecutionStatus status = NodeExecutionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_data", columnDefinition = "jsonb")
    private JsonNode inputData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_data", columnDefinition = "jsonb")
    private JsonNode outputData;

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

    protected NodeExecution() {
    }

    public NodeExecution(Execution execution, String workflowNodeId, String nodeType) {
        this.execution = execution;
        this.workflowNodeId = workflowNodeId;
        this.nodeType = nodeType;
    }

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }

    public void transitionTo(NodeExecutionStatus nextStatus, JsonNode input, JsonNode output, String error) {
        boolean valid = switch (status) {
            case PENDING -> nextStatus == NodeExecutionStatus.RUNNING || nextStatus == NodeExecutionStatus.SKIPPED;
            case RUNNING -> nextStatus == NodeExecutionStatus.SUCCEEDED
                    || nextStatus == NodeExecutionStatus.FAILED
                    || nextStatus == NodeExecutionStatus.SKIPPED;
            case SUCCEEDED, FAILED, SKIPPED -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Invalid node execution status transition: " + status + " -> " + nextStatus);
        }
        if (nextStatus == NodeExecutionStatus.RUNNING) {
            startedAt = Instant.now();
        } else {
            completedAt = Instant.now();
            durationMs = startedAt == null ? 0L : Duration.between(startedAt, completedAt).toMillis();
        }
        inputData = input == null ? null : input.deepCopy();
        outputData = output == null ? null : output.deepCopy();
        errorMessage = error;
        status = nextStatus;
    }

    public UUID getId() { return id; }
    public UUID getExecutionId() { return execution.getId(); }
    public String getWorkflowNodeId() { return workflowNodeId; }
    public String getNodeType() { return nodeType; }
    public NodeExecutionStatus getStatus() { return status; }
    public JsonNode getInputData() { return inputData == null ? null : inputData.deepCopy(); }
    public JsonNode getOutputData() { return outputData == null ? null : outputData.deepCopy(); }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
