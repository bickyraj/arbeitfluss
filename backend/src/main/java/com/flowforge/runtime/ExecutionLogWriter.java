package com.flowforge.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.model.execution.ExecutionLogLevel;

@FunctionalInterface
public interface ExecutionLogWriter {
    void write(ExecutionLogLevel level, String message, JsonNode details);
}
