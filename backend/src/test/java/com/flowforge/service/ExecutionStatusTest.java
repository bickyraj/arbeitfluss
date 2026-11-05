package com.flowforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.NodeExecution;
import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionTriggerType;
import com.flowforge.model.execution.NodeExecutionStatus;
import com.flowforge.model.execution.ExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionStatusTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void workflowAndNodeStatusesOnlyAllowValidTransitions() {
        Workflow workflow = new Workflow("Test", "", true, objectMapper.createObjectNode());
        Execution execution = new Execution(
                workflow, "Test", objectMapper.createObjectNode(),
                ExecutionTriggerType.MANUAL, objectMapper.createObjectNode()
        );
        execution.transitionTo(ExecutionStatus.RUNNING, null);
        execution.transitionTo(ExecutionStatus.CANCELLING, null);
        execution.transitionTo(ExecutionStatus.CANCELLED, "cancelled", "EXECUTION_CANCELLED");
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(execution.getErrorCode()).isEqualTo("EXECUTION_CANCELLED");
        assertThatThrownBy(() -> execution.transitionTo(ExecutionStatus.SUCCEEDED, null))
                .isInstanceOf(IllegalStateException.class);

        Execution successfulExecution = new Execution(
                workflow, "Test", objectMapper.createObjectNode(),
                ExecutionTriggerType.MANUAL, objectMapper.createObjectNode()
        );
        successfulExecution.transitionTo(ExecutionStatus.RUNNING, null);
        successfulExecution.transitionTo(ExecutionStatus.SUCCEEDED, null);
        assertThat(successfulExecution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(successfulExecution.getCompletedAt()).isNotNull();
        assertThatThrownBy(() -> successfulExecution.transitionTo(ExecutionStatus.FAILED, "late failure"))
                .isInstanceOf(IllegalStateException.class);

        NodeExecution nodeExecution = new NodeExecution(successfulExecution, "editor-node-7", "set-data");
        nodeExecution.transitionTo(NodeExecutionStatus.RUNNING, null, null, null);
        nodeExecution.transitionTo(NodeExecutionStatus.SUCCEEDED, null, objectMapper.createObjectNode().put("ok", true), null);
        assertThat(nodeExecution.getStatus()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        assertThatThrownBy(() -> nodeExecution.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queuedCancellationIsTerminalWithoutInventingAStartTime() {
        Workflow workflow = new Workflow("Test", "", true, objectMapper.createObjectNode());
        Execution execution = new Execution(
                workflow, "Test", objectMapper.createObjectNode(),
                ExecutionTriggerType.MANUAL, objectMapper.createObjectNode()
        );

        execution.transitionTo(ExecutionStatus.CANCELLED, "cancelled", "EXECUTION_CANCELLED");

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getDurationMs()).isZero();
        assertThat(execution.getErrorCode()).isEqualTo("EXECUTION_CANCELLED");
    }
}
