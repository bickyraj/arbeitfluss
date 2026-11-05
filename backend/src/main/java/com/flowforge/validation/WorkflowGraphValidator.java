package com.flowforge.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.executor.NodeExecutionException;
import com.flowforge.executor.NodeExecutorRegistry;
import com.flowforge.runtime.WorkflowGraphPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Component
public class WorkflowGraphValidator {
    private static final Set<String> SUPPORTED_SOURCE_HANDLES = Set.of("output");
    private static final Set<String> CONDITIONAL_SOURCE_HANDLES = Set.of("true", "false");
    private static final String SUPPORTED_TARGET_HANDLE = "input";

    private final NodeExecutorRegistry executorRegistry;

    public WorkflowGraphValidator(NodeExecutorRegistry executorRegistry) {
        this.executorRegistry = executorRegistry;
    }

    public WorkflowGraphPlan validate(JsonNode definition) {
        if (definition == null || !definition.isObject()
                || !definition.path("nodes").isArray()
                || !definition.path("connections").isArray()) {
            throw invalid("Workflow definition must contain nodes and connections arrays");
        }

        JsonNode nodeArray = definition.path("nodes");
        JsonNode connectionArray = definition.path("connections");
        if (nodeArray.isEmpty()) {
            throw invalid("Workflow must contain at least one node");
        }

        Map<String, WorkflowGraphPlan.Node> nodes = new LinkedHashMap<>();
        List<String> triggerIds = new ArrayList<>();
        for (int index = 0; index < nodeArray.size(); index++) {
            JsonNode nodeJson = nodeArray.get(index);
            String label = "Node at index " + index;
            if (!nodeJson.isObject()) {
                throw invalid(label + " must be an object");
            }
            String nodeId = requiredText(nodeJson, "id", label);
            String type = requiredText(nodeJson, "type", "Node '" + nodeId + "'");
            String name = nodeJson.path("name").isTextual() ? nodeJson.path("name").textValue() : nodeId;
            if (nodes.containsKey(nodeId)) {
                throw invalid("Duplicate node ID '" + nodeId + "'");
            }
            if (!executorRegistry.supports(type)) {
                throw invalid("Node '" + nodeId + "' has unsupported type '" + type + "'");
            }
            JsonNode configuration = nodeJson.get("configuration");
            try {
                executorRegistry.getRequired(type).validateConfiguration(configuration);
            } catch (NodeExecutionException exception) {
                throw invalid("Node '" + nodeId + "' has invalid configuration: " + exception.getMessage());
            }
            WorkflowGraphPlan.Node node = new WorkflowGraphPlan.Node(nodeId, type, name, configuration);
            nodes.put(nodeId, node);
            if ("manual-trigger".equals(type) || "webhook-trigger".equals(type)) {
                triggerIds.add(nodeId);
            }
        }

        if (triggerIds.isEmpty()) {
            throw invalid("Workflow must contain one trigger node");
        }
        if (triggerIds.size() > 1) {
            throw invalid("Workflow contains multiple trigger nodes: " + String.join(", ", triggerIds));
        }
        String triggerId = triggerIds.getFirst();

        Map<String, List<WorkflowGraphPlan.Connection>> incoming = new LinkedHashMap<>();
        Map<String, List<WorkflowGraphPlan.Connection>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        nodes.keySet().forEach(nodeId -> {
            incoming.put(nodeId, new ArrayList<>());
            outgoing.put(nodeId, new ArrayList<>());
            indegree.put(nodeId, 0);
        });
        Set<String> edgeKeys = new HashSet<>();
        Set<String> connectionIds = new HashSet<>();

        for (int index = 0; index < connectionArray.size(); index++) {
            JsonNode connectionJson = connectionArray.get(index);
            String label = "Connection at index " + index;
            if (!connectionJson.isObject()) {
                throw invalid(label + " must be an object");
            }
            String connectionId = requiredText(connectionJson, "id", label);
            String sourceId = requiredText(connectionJson, "sourceNodeId", "Connection '" + connectionId + "'");
            String sourceHandle = requiredText(connectionJson, "sourceHandle", "Connection '" + connectionId + "'");
            String targetId = requiredText(connectionJson, "targetNodeId", "Connection '" + connectionId + "'");
            String targetHandle = requiredText(connectionJson, "targetHandle", "Connection '" + connectionId + "'");
            if (!connectionIds.add(connectionId)) {
                throw invalid("Duplicate connection ID '" + connectionId + "'");
            }
            if (!nodes.containsKey(sourceId) || !nodes.containsKey(targetId)) {
                throw invalid("Connection '" + connectionId + "' references missing node '" +
                        (!nodes.containsKey(sourceId) ? sourceId : targetId) + "'");
            }
            if (sourceId.equals(targetId)) {
                throw invalid("Connection '" + connectionId + "' cannot connect node '" + sourceId + "' to itself");
            }
            WorkflowGraphPlan.Node sourceNode = nodes.get(sourceId);
            sourceHandle = normalizeSourceHandle(sourceNode, sourceHandle);
            if (!validSourceHandle(sourceNode, sourceHandle)) {
                throw invalid("Connection '" + connectionId + "' uses unsupported source handle '" + sourceHandle + "'");
            }
            if (!SUPPORTED_TARGET_HANDLE.equals(targetHandle)) {
                throw invalid("Connection '" + connectionId + "' uses unsupported target handle '" + targetHandle + "'");
            }
            String edgeKey = sourceId + "\u0000" + sourceHandle + "\u0000" + targetId + "\u0000" + targetHandle;
            if (!edgeKeys.add(edgeKey)) {
                throw invalid("Duplicate connection from node '" + sourceId + "' to node '" + targetId + "'");
            }
            WorkflowGraphPlan.Connection connection =
                    new WorkflowGraphPlan.Connection(connectionId, sourceId, sourceHandle, targetId, targetHandle);
            incoming.get(targetId).add(connection);
            outgoing.get(sourceId).add(connection);
            indegree.compute(targetId, (ignored, count) -> count + 1);
        }

        if (!incoming.get(triggerId).isEmpty()) {
            throw invalid("Trigger node '" + triggerId + "' cannot have incoming connections");
        }
        for (WorkflowGraphPlan.Node node : nodes.values()) {
            if ("if-condition".equals(node.type())) {
                Set<String> connectedBranches = outgoing.get(node.id()).stream()
                        .map(WorkflowGraphPlan.Connection::sourceHandle)
                        .collect(java.util.stream.Collectors.toSet());
                if (!connectedBranches.containsAll(CONDITIONAL_SOURCE_HANDLES)) {
                    List<String> missing = CONDITIONAL_SOURCE_HANDLES.stream()
                            .filter(handle -> !connectedBranches.contains(handle))
                            .toList();
                    throw invalid("Conditional node '" + node.id() + "' is missing outgoing branch connection(s): "
                            + String.join(", ", missing));
                }
            }
        }

        List<String> topologicalOrder = topologicalOrder(outgoing, indegree);
        Map<String, Integer> executionRank = new HashMap<>();
        for (int index = 0; index < topologicalOrder.size(); index++) {
            executionRank.put(topologicalOrder.get(index), index);
        }
        incoming.values().forEach(connections -> connections.sort(
                Comparator.comparingInt((WorkflowGraphPlan.Connection connection) ->
                                executionRank.get(connection.sourceNodeId()))
                        .thenComparing(WorkflowGraphPlan.Connection::id)
        ));
        Set<String> reachable = reachableFrom(triggerId, outgoing);
        if (reachable.size() != nodes.size()) {
            List<String> unreachable = nodes.keySet().stream().filter(id -> !reachable.contains(id)).toList();
            throw invalid("Workflow contains unreachable nodes: " + String.join(", ", unreachable));
        }

        return new WorkflowGraphPlan(
                triggerId,
                nodes.get(triggerId).type(),
                topologicalOrder.stream().map(nodes::get).toList(),
                incoming,
                outgoing
        );
    }

    private String normalizeSourceHandle(WorkflowGraphPlan.Node sourceNode, String sourceHandle) {
        if (!"if-condition".equals(sourceNode.type())) {
            return sourceHandle;
        }
        return switch (sourceHandle) {
            case "output" -> "true";
            case "output-2" -> "false";
            default -> sourceHandle;
        };
    }

    private boolean validSourceHandle(WorkflowGraphPlan.Node sourceNode, String sourceHandle) {
        return "if-condition".equals(sourceNode.type())
                ? CONDITIONAL_SOURCE_HANDLES.contains(sourceHandle)
                : SUPPORTED_SOURCE_HANDLES.contains(sourceHandle);
    }

    private List<String> topologicalOrder(
            Map<String, List<WorkflowGraphPlan.Connection>> outgoing,
            Map<String, Integer> originalIndegree
    ) {
        Map<String, Integer> indegree = new LinkedHashMap<>(originalIndegree);
        Queue<String> ready = new ArrayDeque<>();
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String nodeId = ready.remove();
            order.add(nodeId);
            for (WorkflowGraphPlan.Connection connection : outgoing.get(nodeId)) {
                String targetId = connection.targetNodeId();
                int nextDegree = indegree.compute(targetId, (ignored, degree) -> degree - 1);
                if (nextDegree == 0) {
                    ready.add(targetId);
                }
            }
        }
        if (order.size() != indegree.size()) {
            List<String> cyclic = indegree.entrySet().stream().filter(entry -> entry.getValue() > 0).map(Map.Entry::getKey).toList();
            throw invalid("Workflow contains a cycle involving nodes: " + String.join(", ", cyclic));
        }
        return order;
    }

    private Set<String> reachableFrom(
            String startNodeId,
            Map<String, List<WorkflowGraphPlan.Connection>> outgoing
    ) {
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(startNodeId);
        while (!queue.isEmpty()) {
            String nodeId = queue.remove();
            if (reachable.add(nodeId)) {
                outgoing.get(nodeId).forEach(connection -> queue.add(connection.targetNodeId()));
            }
        }
        return reachable;
    }

    private String requiredText(JsonNode object, String property, String owner) {
        JsonNode value = object.get(property);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(owner + " must have a non-empty string '" + property + "'");
        }
        return value.textValue();
    }

    private InvalidExecutionRequestException invalid(String message) {
        return new InvalidExecutionRequestException(message);
    }
}
