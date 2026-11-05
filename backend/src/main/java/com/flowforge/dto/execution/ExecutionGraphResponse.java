package com.flowforge.dto.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Read-only visualization of the immutable execution snapshot; never exposes node configuration. */
public record ExecutionGraphResponse(List<Node> nodes, List<Connection> connections) {
    public record Position(double x, double y) {}
    public record Node(String id, String type, String name, Position position) {}
    public record Connection(String id, String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle) {}

    public static ExecutionGraphResponse fromDefinition(JsonNode definition) {
        List<Node> nodes = new ArrayList<>();
        List<Connection> connections = new ArrayList<>();
        if (definition == null) return new ExecutionGraphResponse(nodes, connections);
        if (definition.path("nodes").isArray()) {
            for (JsonNode node : definition.path("nodes")) {
                JsonNode position = node.path("position");
                Position coordinates = position.path("x").isNumber() && position.path("y").isNumber()
                        && Double.isFinite(position.path("x").asDouble()) && Double.isFinite(position.path("y").asDouble())
                        ? new Position(position.path("x").asDouble(), position.path("y").asDouble()) : null;
                nodes.add(new Node(node.path("id").asText(), node.path("type").asText(),
                        node.path("name").asText(node.path("id").asText()), coordinates));
            }
        }
        if (definition.path("connections").isArray()) {
            for (JsonNode edge : definition.path("connections")) {
                connections.add(new Connection(edge.path("id").asText(), edge.path("sourceNodeId").asText(),
                        edge.path("sourceHandle").asText(), edge.path("targetNodeId").asText(), edge.path("targetHandle").asText()));
            }
        }
        return new ExecutionGraphResponse(List.copyOf(nodes), List.copyOf(connections));
    }
}
