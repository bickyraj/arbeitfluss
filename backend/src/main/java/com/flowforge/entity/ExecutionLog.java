package com.flowforge.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.model.execution.ExecutionLogLevel;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_logs")
public class ExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_execution_id")
    private NodeExecution nodeExecution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionLogLevel level;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExecutionLog() {
    }

    public ExecutionLog(Execution execution, NodeExecution nodeExecution, ExecutionLogLevel level,
                        String message, JsonNode details) {
        this.execution = execution;
        this.nodeExecution = nodeExecution;
        this.level = level;
        this.message = message;
        this.details = details == null ? null : details.deepCopy();
    }

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getExecutionId() { return execution.getId(); }
    public UUID getNodeExecutionId() { return nodeExecution == null ? null : nodeExecution.getId(); }
    public ExecutionLogLevel getLevel() { return level; }
    public String getMessage() { return message; }
    public JsonNode getDetails() { return details == null ? null : details.deepCopy(); }
    public Instant getCreatedAt() { return createdAt; }
}
