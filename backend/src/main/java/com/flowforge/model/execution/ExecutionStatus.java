package com.flowforge.model.execution;

public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
