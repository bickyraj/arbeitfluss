package com.flowforge.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.executor.NodeExecutionException;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkflowExecutionRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutionRuntime.class);
    private static final Duration EXECUTION_TIMEOUT = Duration.ofMinutes(5);

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
            stateService.failExecution(executionId, exception.getMessage());
            return;
        }

        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Map<String, Map<String, JsonNode>> branchOutputs = new LinkedHashMap<>();
        Instant deadline = Instant.now().plus(EXECUTION_TIMEOUT);

        for (int index = 0; index < graph.executionOrder().size(); index++) {
            WorkflowGraphPlan.Node node = graph.executionOrder().get(index);
            JsonNode input = inputFor(node, graph, snapshot, outputs);
            if (Instant.now().isAfter(deadline)) {
                failExecution(executionId, graph, index, "Workflow execution timed out.");
                return;
            }

            UUID nodeExecutionId = stateService.beginNode(executionId, node.id(), input);
            Map<String, JsonNode> upstreamOutputs = upstreamOutputs(node, graph, branchOutputs, outputs);
            ExecutionContext context = new ExecutionContext(
                    executionId,
                    nodeExecutionId,
                    snapshot.workflowId(),
                    node.id(),
                    node.configuration(),
                    upstreamOutputs,
                    deadline,
                    (level, message, details) -> stateService.appendLog(
                            executionId, nodeExecutionId, level, message, details
                    )
            );

            try {
                if (context.isExpired()) {
                    throw new NodeExecutionException("Workflow execution timed out.");
                }
                NodeExecutor executor = executorRegistry.getRequired(node.type());
                JsonNode output = executor.execute(input == null ? NullNode.getInstance() : input.deepCopy(), context);
                if (context.isExpired()) {
                    throw new NodeExecutionException("Workflow execution timed out.");
                }
                JsonNode safeOutput = output == null ? NullNode.getInstance() : output.deepCopy();
                stateService.succeedNode(executionId, node.id(), input, safeOutput);
                outputs.put(node.id(), safeOutput.deepCopy());

                Map<String, JsonNode> currentBranch = new LinkedHashMap<>(upstreamOutputs);
                currentBranch.put(node.id(), safeOutput.deepCopy());
                branchOutputs.put(node.id(), currentBranch);
            } catch (Exception exception) {
                String safeError = safeNodeError(node, exception);
                stateService.failNode(executionId, node.id(), input, safeError);
                stateService.appendLog(
                        executionId,
                        nodeExecutionId,
                        ExecutionLogLevel.ERROR,
                        safeError,
                        null
                );
                failExecution(executionId, graph, index + 1, safeError);
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
            Map<String, JsonNode> outputs
    ) {
        if (node.id().equals(graph.triggerNodeId())) {
            return snapshot.triggerInput();
        }
        WorkflowGraphPlan.Connection incoming = graph.incomingConnections().get(node.id());
        JsonNode output = incoming == null ? null : outputs.get(incoming.sourceNodeId());
        return output == null ? NullNode.getInstance() : output.deepCopy();
    }

    private Map<String, JsonNode> upstreamOutputs(
            WorkflowGraphPlan.Node node,
            WorkflowGraphPlan graph,
            Map<String, Map<String, JsonNode>> branchOutputs,
            Map<String, JsonNode> outputs
    ) {
        WorkflowGraphPlan.Connection incoming = graph.incomingConnections().get(node.id());
        if (incoming == null) {
            return Map.of();
        }
        Map<String, JsonNode> ancestors = branchOutputs.get(incoming.sourceNodeId());
        if (ancestors != null) {
            Map<String, JsonNode> copy = new LinkedHashMap<>();
            ancestors.forEach((nodeId, output) -> copy.put(nodeId, output.deepCopy()));
            return copy;
        }
        JsonNode parentOutput = outputs.get(incoming.sourceNodeId());
        return parentOutput == null
                ? Map.of()
                : Map.of(incoming.sourceNodeId(), parentOutput.deepCopy());
    }

    private void failExecution(
            UUID executionId,
            WorkflowGraphPlan graph,
            int firstSkippedIndex,
            String safeError
    ) {
        for (int index = firstSkippedIndex; index < graph.executionOrder().size(); index++) {
            stateService.skipNode(executionId, graph.executionOrder().get(index).id());
        }
        stateService.failExecution(executionId, safeError);
        LOGGER.info("Workflow execution {} completed with failure", executionId);
    }

    private String safeNodeError(WorkflowGraphPlan.Node node, Exception exception) {
        if (exception instanceof NodeExecutionException && exception.getMessage() != null) {
            return "Node '" + node.name() + "' failed: " + exception.getMessage();
        }
        return "Node '" + node.name() + "' failed.";
    }
}
