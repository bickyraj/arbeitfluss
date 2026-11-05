package com.flowforge.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public record WorkflowGraphPlan(
        String triggerNodeId,
        List<Node> executionOrder,
        Map<String, Connection> incomingConnections,
        Map<String, List<Connection>> outgoingConnections
) {
    public WorkflowGraphPlan {
        executionOrder = List.copyOf(executionOrder);
        incomingConnections = Collections.unmodifiableMap(new LinkedHashMap<>(incomingConnections));
        Map<String, List<Connection>> orderedOutgoing = outgoingConnections.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue()),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        outgoingConnections = Collections.unmodifiableMap(orderedOutgoing);
    }

    public record Node(String id, String type, String name, JsonNode configuration) {
        public Node {
            configuration = configuration == null ? null : configuration.deepCopy();
        }

        @Override
        public JsonNode configuration() {
            return configuration == null ? null : configuration.deepCopy();
        }
    }

    public record Connection(String id, String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle) {
    }
}
