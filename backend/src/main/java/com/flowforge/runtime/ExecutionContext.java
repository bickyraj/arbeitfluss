package com.flowforge.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ExecutionContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionContext.class);
    private final UUID executionId;
    private final UUID nodeExecutionId;
    private final UUID workflowId;
    private final String workflowNodeId;
    private final JsonNode nodeConfiguration;
    private final Map<String, JsonNode> upstreamOutputs;
    private final Instant deadline;
    private final ExecutionLogWriter logWriter;
    private final BooleanSupplier cancellationRequested;
    private final AtomicBoolean cancellationTriggered = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> cancellationHandlers = new CopyOnWriteArrayList<>();

    public ExecutionContext(
            UUID executionId,
            UUID nodeExecutionId,
            UUID workflowId,
            String workflowNodeId,
            JsonNode nodeConfiguration,
            Map<String, JsonNode> upstreamOutputs,
            Instant deadline,
            BooleanSupplier cancellationRequested,
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
        this.cancellationRequested = cancellationRequested;
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
    public long remainingTimeMillis() {
        return Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
    }

    public boolean isCancellationRequested() {
        return cancellationTriggered.get() || cancellationRequested.getAsBoolean();
    }

    public AutoCloseable onCancellation(Runnable handler) {
        cancellationHandlers.add(handler);
        if (isCancellationRequested()) {
            if (cancellationTriggered.get()) {
                runCancellationHandler(handler);
            } else {
                triggerCancellation();
            }
        }
        return () -> cancellationHandlers.remove(handler);
    }

    public void cancelActiveOperations() {
        triggerCancellation();
    }

    private void triggerCancellation() {
        if (cancellationTriggered.compareAndSet(false, true)) {
            cancellationHandlers.forEach(this::runCancellationHandler);
        }
    }

    private void runCancellationHandler(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException ignored) {
            LOGGER.warn("Could not abort an active operation for execution {}", executionId);
        }
    }

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
