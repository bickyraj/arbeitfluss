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
        execution.transitionTo(ExecutionStatus.SUCCEEDED, null);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.getCompletedAt()).isNotNull();
        assertThatThrownBy(() -> execution.transitionTo(ExecutionStatus.FAILED, "late failure"))
                .isInstanceOf(IllegalStateException.class);

        NodeExecution nodeExecution = new NodeExecution(execution, "editor-node-7", "set-data");
        nodeExecution.transitionTo(NodeExecutionStatus.RUNNING, null, null, null);
        nodeExecution.transitionTo(NodeExecutionStatus.SUCCEEDED, null, objectMapper.createObjectNode().put("ok", true), null);
        assertThat(nodeExecution.getStatus()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        assertThatThrownBy(() -> nodeExecution.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
