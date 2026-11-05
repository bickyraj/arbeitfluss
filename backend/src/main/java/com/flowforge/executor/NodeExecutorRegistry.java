package com.flowforge.executor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class NodeExecutorRegistry {
    private final Map<String, NodeExecutor> executors;

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        this.executors = executors.stream().collect(Collectors.toUnmodifiableMap(
                NodeExecutor::nodeType,
                Function.identity(),
                (first, duplicate) -> {
                    throw new IllegalStateException("Duplicate node executor for type: " + first.nodeType());
                }
        ));
    }

    public NodeExecutor getRequired(String nodeType) {
        NodeExecutor executor = executors.get(nodeType);
        if (executor == null) {
            throw new IllegalArgumentException("No executor registered for node type: " + nodeType);
        }
        return executor;
    }

    public boolean supports(String nodeType) {
        return executors.containsKey(nodeType);
    }
}
