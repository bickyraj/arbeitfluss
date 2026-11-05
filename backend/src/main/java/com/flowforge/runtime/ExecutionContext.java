package com.flowforge.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ExecutionContext {
    private final UUID executionId;
    private final UUID nodeExecutionId;
    private final UUID workflowId;
    private final String workflowNodeId;
    private final JsonNode nodeConfiguration;
    private final Map<String, JsonNode> upstreamOutputs;
    private final Instant deadline;
    private final ExecutionLogWriter logWriter;

    public ExecutionContext(
            UUID executionId,
            UUID nodeExecutionId,
            UUID workflowId,
            String workflowNodeId,
            JsonNode nodeConfiguration,
            Map<String, JsonNode> upstreamOutputs,
            Instant deadline,
            ExecutionLogWriter logWriter
    ) {
        this.executionId = executionId;
        this.nodeExecutionId = nodeExecutionId;
        this.workflowId = workflowId;
        this.workflowNodeId = workflowNodeId;
        this.nodeConfiguration = nodeConfiguration == null ? null : nodeConfiguration.deepCopy();
        Map<String, JsonNode> outputsCopy = new LinkedHashMap<>();
        upstreamOutputs.forEach((nodeId, output) -> outputsCopy.put(nodeId, output.deepCopy()));
        this.upstreamOutputs = Map.copyOf(outputsCopy);
        this.deadline = deadline;
        this.logWriter = logWriter;
    }

    public UUID executionId() { return executionId; }
    public UUID nodeExecutionId() { return nodeExecutionId; }
    public UUID workflowId() { return workflowId; }
    public String workflowNodeId() { return workflowNodeId; }
    public JsonNode nodeConfiguration() { return nodeConfiguration == null ? null : nodeConfiguration.deepCopy(); }
    public Map<String, JsonNode> upstreamOutputs() {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        upstreamOutputs.forEach((nodeId, output) -> copy.put(nodeId, output.deepCopy()));
        return Map.copyOf(copy);
    }
    public Instant deadline() { return deadline; }

    public boolean isExpired() {
        return !Instant.now().isBefore(deadline);
    }

    public void log(com.flowforge.model.execution.ExecutionLogLevel level, String message, JsonNode details) {
        if (isExpired()) {
            throw new IllegalStateException("Workflow execution timed out");
        }
        logWriter.write(level, message, details == null ? null : details.deepCopy());
    }
}
