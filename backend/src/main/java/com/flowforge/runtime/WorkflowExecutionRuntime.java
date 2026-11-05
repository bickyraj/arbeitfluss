package com.flowforge.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.executor.NodeExecutionException;
import com.flowforge.executor.NodeExecutionResult;
import com.flowforge.executor.NodeExecutor;
import com.flowforge.executor.NodeExecutorRegistry;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.model.execution.ExecutionRunSnapshot;
import com.flowforge.service.ExecutionStateService;
import com.flowforge.validation.WorkflowGraphValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkflowExecutionRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutionRuntime.class);

    private final ExecutionStateService stateService;
    private final WorkflowGraphValidator graphValidator;
    private final NodeExecutorRegistry executorRegistry;

    public WorkflowExecutionRuntime(
            ExecutionStateService stateService,
            WorkflowGraphValidator graphValidator,
            NodeExecutorRegistry executorRegistry
    ) {
        this.stateService = stateService;
        this.graphValidator = graphValidator;
        this.executorRegistry = executorRegistry;
    }

    public void execute(UUID executionId) {
        Optional<ExecutionRunSnapshot> snapshotOption = stateService.beginExecution(executionId);
        if (snapshotOption.isEmpty()) {
            LOGGER.warn("Workflow execution {} was not queued when the worker attempted to start it", executionId);
            return;
        }

        LOGGER.info("Workflow execution {} entered RUNNING", executionId);
        ExecutionRunSnapshot snapshot = snapshotOption.get();
        WorkflowGraphPlan graph;
        try {
            graph = graphValidator.validate(snapshot.definition());
        } catch (InvalidExecutionRequestException exception) {
            stateService.failExecution(executionId, exception.getMessage(), "INVALID_WORKFLOW");
            return;
        }

        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Map<String, Map<String, JsonNode>> branchOutputs = new LinkedHashMap<>();
        Map<String, Boolean> activeEdges = new LinkedHashMap<>();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(snapshot.timeoutSeconds()));

        for (int index = 0; index < graph.executionOrder().size(); index++) {
            if (stateService.isCancellationRequested(executionId)) {
                skipRemaining(executionId, graph, index);
                stateService.cancelExecution(executionId);
                return;
            }
            WorkflowGraphPlan.Node node = graph.executionOrder().get(index);
            List<WorkflowGraphPlan.Connection> incoming = graph.incomingConnections().getOrDefault(node.id(), List.of());
            if (incoming.stream().anyMatch(connection -> !activeEdges.containsKey(connection.id()))) {
                failExecution(executionId, graph, index, "Workflow graph could not resolve all incoming connections.");
                return;
            }
            List<WorkflowGraphPlan.Connection> activeIncoming = incoming.stream()
                    .filter(connection -> Boolean.TRUE.equals(activeEdges.get(connection.id())))
                    .toList();
            if (!node.id().equals(graph.triggerNodeId()) && activeIncoming.isEmpty()) {
                stateService.skipNode(executionId, node.id());
                deactivateOutgoing(node, graph, activeEdges);
                continue;
            }
            JsonNode input = inputFor(node, graph, snapshot, activeIncoming, outputs);
            if (Instant.now().isAfter(deadline)) {
                failExecution(
                        executionId,
                        graph,
                        index,
                        "Workflow execution timed out.",
                        "EXECUTION_TIMEOUT"
                );
                return;
            }

            Optional<UUID> nodeExecution = stateService.beginNode(executionId, node.id(), input);
            if (nodeExecution.isEmpty()) {
                skipRemaining(executionId, graph, index);
                stateService.cancelExecution(executionId);
                return;
            }
            UUID nodeExecutionId = nodeExecution.get();
            Map<String, JsonNode> upstreamOutputs = upstreamOutputs(activeIncoming, branchOutputs, outputs);
            ExecutionContext context = new ExecutionContext(
                    executionId,
                    nodeExecutionId,
                    snapshot.workflowId(),
                    node.id(),
                    node.configuration(),
                    upstreamOutputs,
                    deadline,
                    () -> stateService.isCancellationRequested(executionId),
                    (level, message, details) -> stateService.appendLog(
                            executionId, nodeExecutionId, level, message, details
                    )
            );

            try {
                if (context.isExpired()) {
                    throw new NodeExecutionException("Workflow execution timed out.");
                }
                NodeExecutor executor = executorRegistry.getRequired(node.type());
                NodeExecutionResult result = executeWithinDeadline(
                        executor,
                        input == null ? NullNode.getInstance() : input.deepCopy(),
                        context
                );
                if (context.isExpired()) {
                    throw new NodeExecutionException("Workflow execution timed out.");
                }
                JsonNode safeOutput = result.output() == null ? NullNode.getInstance() : result.output();
                stateService.succeedNode(executionId, node.id(), input, safeOutput);
                outputs.put(node.id(), safeOutput.deepCopy());
                Map<String, JsonNode> currentBranch = branchContext(activeIncoming, branchOutputs, upstreamOutputs);
                currentBranch.put(node.id(), safeOutput.deepCopy());
                branchOutputs.put(node.id(), currentBranch);
                if (stateService.isCancellationRequested(executionId)) {
                    skipRemaining(executionId, graph, index + 1);
                    stateService.cancelExecution(executionId);
                    return;
                }
                activateOutgoing(node, graph, result.selectedOutputHandle(), activeEdges);
            } catch (Exception exception) {
                String safeError = safeNodeError(node, exception);
                JsonNode failureOutput = exception instanceof NodeExecutionException nodeException
                        ? nodeException.failureOutput()
                        : null;
                stateService.failNode(executionId, node.id(), input, failureOutput, safeError);
                String errorCode = exception instanceof NodeExecutionException nodeException
                        ? nodeException.errorCode()
                        : "EXECUTION_FAILED";
                stateService.appendLog(
                        executionId,
                        nodeExecutionId,
                        ExecutionLogLevel.ERROR,
                        safeError,
                        JsonNodeFactory.instance.objectNode().put("errorCode", errorCode)
                );
                boolean timedOut = context.isExpired() || "Workflow execution timed out.".equals(exception.getMessage());
                failExecution(
                        executionId,
                        graph,
                        index + 1,
                        safeError,
                        timedOut ? "EXECUTION_TIMEOUT" : errorCode
                );
                return;
            }
        }
        stateService.succeedExecution(executionId);
        LOGGER.info("Workflow execution {} completed successfully", executionId);
    }

    private JsonNode inputFor(
            WorkflowGraphPlan.Node node,
            WorkflowGraphPlan graph,
            ExecutionRunSnapshot snapshot,
            List<WorkflowGraphPlan.Connection> activeIncoming,
            Map<String, JsonNode> outputs
    ) {
        if (node.id().equals(graph.triggerNodeId())) {
            return snapshot.triggerInput();
        }
        if (activeIncoming.size() == 1) {
            JsonNode output = outputs.get(activeIncoming.getFirst().sourceNodeId());
            return output == null ? NullNode.getInstance() : output.deepCopy();
        }
        ObjectNode aggregate = JsonNodeFactory.instance.objectNode();
        ObjectNode inputs = aggregate.putObject("inputs");
        activeIncoming.forEach(connection -> {
            JsonNode output = outputs.get(connection.sourceNodeId());
            inputs.set(connection.sourceNodeId(), output == null ? NullNode.getInstance() : output.deepCopy());
        });
        return aggregate;
    }

    private Map<String, JsonNode> upstreamOutputs(
            List<WorkflowGraphPlan.Connection> activeIncoming,
            Map<String, Map<String, JsonNode>> branchOutputs,
            Map<String, JsonNode> outputs
    ) {
        if (activeIncoming.isEmpty()) {
            return Map.of();
        }
        if (activeIncoming.size() > 1) {
            Map<String, JsonNode> directInputs = new LinkedHashMap<>();
            activeIncoming.forEach(connection -> {
                JsonNode output = outputs.get(connection.sourceNodeId());
                directInputs.put(
                        connection.sourceNodeId(),
                        output == null ? NullNode.getInstance() : output.deepCopy()
                );
            });
            return directInputs;
        }
        String parentId = activeIncoming.getFirst().sourceNodeId();
        Map<String, JsonNode> ancestors = branchOutputs.get(parentId);
        if (ancestors != null) {
            Map<String, JsonNode> copy = new LinkedHashMap<>();
            ancestors.forEach((nodeId, output) -> copy.put(nodeId, output.deepCopy()));
            return copy;
        }
        JsonNode parentOutput = outputs.get(parentId);
        return parentOutput == null ? Map.of() : Map.of(parentId, parentOutput.deepCopy());
    }

    private Map<String, JsonNode> branchContext(
            List<WorkflowGraphPlan.Connection> activeIncoming,
            Map<String, Map<String, JsonNode>> branchOutputs,
            Map<String, JsonNode> nodeInputs
    ) {
        Map<String, JsonNode> branchContext = new LinkedHashMap<>();
        if (activeIncoming.size() > 1) {
            activeIncoming.forEach(connection -> {
                Map<String, JsonNode> parentContext = branchOutputs.get(connection.sourceNodeId());
                if (parentContext != null) {
                    parentContext.forEach((nodeId, output) -> branchContext.putIfAbsent(nodeId, output.deepCopy()));
                }
            });
        } else {
            nodeInputs.forEach((nodeId, output) -> branchContext.put(nodeId, output.deepCopy()));
        }
        return branchContext;
    }

    private void activateOutgoing(
            WorkflowGraphPlan.Node node,
            WorkflowGraphPlan graph,
            String selectedOutputHandle,
            Map<String, Boolean> activeEdges
    ) {
        List<WorkflowGraphPlan.Connection> outgoing =
                graph.outgoingConnections().getOrDefault(node.id(), List.of());
        if (selectedOutputHandle == null) {
            outgoing.forEach(connection -> activeEdges.put(connection.id(), true));
            return;
        }
        if (!"if-condition".equals(node.type())
                || !selectedOutputHandle.equals("true") && !selectedOutputHandle.equals("false")) {
            throw new NodeExecutionException("Node returned an invalid output branch.");
        }
        boolean hasSelectedEdge = outgoing.stream()
                .anyMatch(connection -> selectedOutputHandle.equals(connection.sourceHandle()));
        if (!hasSelectedEdge) {
            throw new NodeExecutionException("Selected conditional branch has no outgoing connection.");
        }
        outgoing.forEach(connection ->
                activeEdges.put(connection.id(), selectedOutputHandle.equals(connection.sourceHandle())));
    }

    private void deactivateOutgoing(
            WorkflowGraphPlan.Node node,
            WorkflowGraphPlan graph,
            Map<String, Boolean> activeEdges
    ) {
        graph.outgoingConnections().getOrDefault(node.id(), List.of())
                .forEach(connection -> activeEdges.put(connection.id(), false));
    }

    private void failExecution(
            UUID executionId,
            WorkflowGraphPlan graph,
            int firstSkippedIndex,
            String safeError
    ) {
        failExecution(executionId, graph, firstSkippedIndex, safeError, "EXECUTION_FAILED");
    }

    private void failExecution(
            UUID executionId,
            WorkflowGraphPlan graph,
            int firstSkippedIndex,
            String safeError,
            String errorCode
    ) {
        for (int index = firstSkippedIndex; index < graph.executionOrder().size(); index++) {
            stateService.skipNode(executionId, graph.executionOrder().get(index).id());
        }
        stateService.failExecution(executionId, safeError, errorCode);
        LOGGER.info("Workflow execution {} completed with failure", executionId);
    }

    private void skipRemaining(UUID executionId, WorkflowGraphPlan graph, int firstSkippedIndex) {
        for (int index = firstSkippedIndex; index < graph.executionOrder().size(); index++) {
            stateService.skipNode(executionId, graph.executionOrder().get(index).id());
        }
    }

    private String safeNodeError(WorkflowGraphPlan.Node node, Exception exception) {
        if (exception instanceof NodeExecutionException && exception.getMessage() != null) {
            return "Node '" + node.name() + "' failed: " + exception.getMessage();
        }
        return "Node '" + node.name() + "' failed.";
    }

    private NodeExecutionResult executeWithinDeadline(
            NodeExecutor executor,
            JsonNode input,
            ExecutionContext context
    ) throws Exception {
        FutureTask<NodeExecutionResult> task = new FutureTask<>(
                () -> executor.executeResult(input, context)
        );
        Thread.ofVirtual()
                .name("workflow-node-" + context.workflowNodeId())
                .start(task);
        long remainingNanos = Duration.between(Instant.now(), context.deadline()).toNanos();
        if (remainingNanos <= 0) {
            context.cancelActiveOperations();
            task.cancel(true);
            throw new NodeExecutionException("Workflow execution timed out.", "EXECUTION_TIMEOUT");
        }
        try {
            while (true) {
                if (executor.abortOnWorkflowCancellation() && context.isCancellationRequested()) {
                    context.cancelActiveOperations();
                    task.cancel(true);
                    throw new NodeExecutionException("Execution cancellation requested.", "EXECUTION_CANCELLED");
                }
                long remaining = Duration.between(Instant.now(), context.deadline()).toNanos();
                if (remaining <= 0) {
                    context.cancelActiveOperations();
                    task.cancel(true);
                    throw new NodeExecutionException("Workflow execution timed out.", "EXECUTION_TIMEOUT");
                }
                try {
                    return task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException pollTimeout) {
                    if (Instant.now().isAfter(context.deadline())) {
                        context.cancelActiveOperations();
                        task.cancel(true);
                        throw new NodeExecutionException("Workflow execution timed out.", "EXECUTION_TIMEOUT");
                    }
                }
            }
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nodeException) {
                throw nodeException;
            }
            throw new NodeExecutionException("Node execution failed.");
        } catch (InterruptedException exception) {
            context.cancelActiveOperations();
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new NodeExecutionException("Execution worker was interrupted.");
        }
    }
}
