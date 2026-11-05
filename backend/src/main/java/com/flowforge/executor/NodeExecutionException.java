package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;

public class NodeExecutionException extends RuntimeException {
    private final String errorCode;
    private final JsonNode failureOutput;

    public NodeExecutionException(String message) {
        this(message, "EXECUTION_FAILED", null);
    }

    public NodeExecutionException(String message, String errorCode) {
        this(message, errorCode, null);
    }

    public NodeExecutionException(String message, String errorCode, JsonNode failureOutput) {
        super(message);
        this.errorCode = errorCode;
        this.failureOutput = failureOutput == null ? null : failureOutput.deepCopy();
    }

    public String errorCode() {
        return errorCode;
    }

    public JsonNode failureOutput() {
        return failureOutput == null ? null : failureOutput.deepCopy();
    }
}
