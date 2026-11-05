package com.flowforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.Execution;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.entity.NodeExecution;
import com.flowforge.exception.ResourceNotFoundException;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.model.execution.ExecutionRunSnapshot;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.model.execution.NodeExecutionStatus;
import com.flowforge.repository.ExecutionLogRepository;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.NodeExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ExecutionStateService {
    private final ExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final ObjectMapper objectMapper;

    public ExecutionStateService(
            ExecutionRepository executionRepository,
            NodeExecutionRepository nodeExecutionRepository,
            ExecutionLogRepository executionLogRepository,
            ObjectMapper objectMapper
    ) {
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.executionLogRepository = executionLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<ExecutionRunSnapshot> beginExecution(UUID executionId) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null || execution.getStatus() != ExecutionStatus.QUEUED) {
            return Optional.empty();
        }
        execution.transitionTo(ExecutionStatus.RUNNING, null);
        return Optional.of(new ExecutionRunSnapshot(
                execution.getId(),
                execution.getWorkflowId(),
                execution.getWorkflowDefinition(),
                execution.getTriggerInput()
        ));
    }

    @Transactional
    public UUID beginNode(UUID executionId, String workflowNodeId, JsonNode input) {
        NodeExecution node = findNodeExecution(executionId, workflowNodeId);
        node.transitionTo(NodeExecutionStatus.RUNNING, input, null, null);
        return node.getId();
    }

    @Transactional
    public void succeedNode(UUID executionId, String workflowNodeId, JsonNode input, JsonNode output) {
        findNodeExecution(executionId, workflowNodeId)
                .transitionTo(NodeExecutionStatus.SUCCEEDED, input, output, null);
    }

    @Transactional
    public void failNode(UUID executionId, String workflowNodeId, JsonNode input, String safeError) {
        NodeExecution node = findNodeExecution(executionId, workflowNodeId);
        if (node.getStatus() == NodeExecutionStatus.RUNNING) {
            node.transitionTo(NodeExecutionStatus.FAILED, input, null, safeError);
        }
    }

    @Transactional
    public void skipNode(UUID executionId, String workflowNodeId) {
        NodeExecution node = findNodeExecution(executionId, workflowNodeId);
        if (node.getStatus() == NodeExecutionStatus.PENDING) {
            node.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null);
        }
    }

    @Transactional
    public void succeedExecution(UUID executionId) {
        Execution execution = findExecution(executionId);
        if (execution.getStatus() == ExecutionStatus.RUNNING) {
            execution.transitionTo(ExecutionStatus.SUCCEEDED, null);
        }
    }

    @Transactional
    public void failExecution(UUID executionId, String safeError) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution != null && (execution.getStatus() == ExecutionStatus.RUNNING
                || execution.getStatus() == ExecutionStatus.QUEUED)) {
            execution.transitionTo(ExecutionStatus.FAILED, safeError);
            executionLogRepository.save(new ExecutionLog(
                    execution,
                    null,
                    ExecutionLogLevel.ERROR,
                    safeError,
                    objectMapper.createObjectNode()
            ));
        }
    }

    @Transactional
    public void appendLog(
            UUID executionId,
            UUID nodeExecutionId,
            ExecutionLogLevel level,
            String message,
            JsonNode details
    ) {
        Execution execution = findExecution(executionId);
        NodeExecution nodeExecution = nodeExecutionId == null
                ? null
                : nodeExecutionRepository.findById(nodeExecutionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Node execution not found"));
        executionLogRepository.save(new ExecutionLog(execution, nodeExecution, level, message, details));
    }

    private Execution findExecution(UUID executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
    }

    private NodeExecution findNodeExecution(UUID executionId, String workflowNodeId) {
        return nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, workflowNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Node execution not found for node: " + workflowNodeId));
    }
}
