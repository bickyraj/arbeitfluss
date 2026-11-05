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
        Execution execution = executionRepository.findByIdForUpdate(executionId).orElse(null);
        if (execution == null || execution.getStatus() != ExecutionStatus.QUEUED) {
            return Optional.empty();
        }
        execution.transitionTo(ExecutionStatus.RUNNING, null);
        return Optional.of(new ExecutionRunSnapshot(
                execution.getId(),
                execution.getWorkflowId(),
                execution.getWorkflowDefinition(),
                execution.getTriggerInput(),
                execution.getTimeoutSeconds()
        ));
    }

    @Transactional
    public Optional<UUID> beginNode(UUID executionId, String workflowNodeId, JsonNode input) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            return Optional.empty();
        }
        NodeExecution node = findNodeExecution(executionId, workflowNodeId);
        node.transitionTo(NodeExecutionStatus.RUNNING, input, null, null);
        return Optional.of(node.getId());
    }

    @Transactional
    public void succeedNode(UUID executionId, String workflowNodeId, JsonNode input, JsonNode output) {
        findNodeExecution(executionId, workflowNodeId)
                .transitionTo(NodeExecutionStatus.SUCCEEDED, input, output, null);
    }

    @Transactional
    public void failNode(UUID executionId, String workflowNodeId, JsonNode input, String safeError) {
        failNode(executionId, workflowNodeId, input, null, safeError);
    }

    @Transactional
    public void failNode(
            UUID executionId,
            String workflowNodeId,
            JsonNode input,
            JsonNode output,
            String safeError
    ) {
        NodeExecution node = findNodeExecution(executionId, workflowNodeId);
        if (node.getStatus() == NodeExecutionStatus.RUNNING) {
            node.transitionTo(NodeExecutionStatus.FAILED, input, output, safeError);
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
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
        if (execution.getStatus() == ExecutionStatus.RUNNING) {
            execution.transitionTo(ExecutionStatus.SUCCEEDED, null);
        } else if (execution.getStatus() == ExecutionStatus.CANCELLING) {
            finishCancellation(execution);
        }
    }

    @Transactional
    public void failExecution(UUID executionId, String safeError) {
        failExecution(executionId, safeError, "EXECUTION_FAILED");
    }

    @Transactional
    public void failExecution(UUID executionId, String safeError, String errorCode) {
        Execution execution = executionRepository.findByIdForUpdate(executionId).orElse(null);
        if (execution != null && (execution.getStatus() == ExecutionStatus.RUNNING
                || execution.getStatus() == ExecutionStatus.QUEUED
                || execution.getStatus() == ExecutionStatus.CANCELLING)) {
            reconcileNodesBeforeTerminal(executionId, safeError);
            if (execution.getStatus() == ExecutionStatus.CANCELLING) {
                finishCancellation(execution);
                appendLifecycleLog(
                        execution,
                        ExecutionLogLevel.INFO,
                        "Execution cancelled after the active node stopped.",
                        "EXECUTION_CANCELLED"
                );
                return;
            }
            execution.transitionTo(ExecutionStatus.FAILED, safeError, errorCode);
            executionLogRepository.save(new ExecutionLog(
                    execution,
                    null,
                    ExecutionLogLevel.ERROR,
                    safeError,
                    detailsWithCode(errorCode)
            ));
        }
    }

    @Transactional
    public ExecutionStatus requestCancellation(UUID executionId) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
        switch (execution.getStatus()) {
            case QUEUED -> {
                execution.transitionTo(ExecutionStatus.CANCELLED, "Execution cancelled before it started.", "EXECUTION_CANCELLED");
                skipPendingNodes(executionId);
                appendLifecycleLog(execution, ExecutionLogLevel.INFO, "Execution cancelled before it started.", "EXECUTION_CANCELLED");
            }
            case RUNNING -> {
                execution.transitionTo(ExecutionStatus.CANCELLING, null);
                appendLifecycleLog(execution, ExecutionLogLevel.INFO, "Cancellation requested; the active node will finish before execution stops.", "EXECUTION_CANCELLING");
            }
            case CANCELLING -> { }
            case SUCCEEDED, FAILED, CANCELLED -> throw new com.flowforge.exception.ExecutionConflictException(
                    "Only queued or running executions can be cancelled."
            );
        }
        return execution.getStatus();
    }

    @Transactional(readOnly = true)
    public boolean isCancellationRequested(UUID executionId) {
        return executionRepository.findById(executionId)
                .map(execution -> execution.getStatus() == ExecutionStatus.CANCELLING)
                .orElse(true);
    }

    @Transactional
    public void cancelExecution(UUID executionId) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
        if (execution.getStatus() == ExecutionStatus.CANCELLING || execution.getStatus() == ExecutionStatus.QUEUED) {
            finishCancellation(execution);
            skipPendingNodes(executionId);
            appendLifecycleLog(execution, ExecutionLogLevel.INFO, "Execution cancelled.", "EXECUTION_CANCELLED");
        }
    }

    @Transactional
    public void recoverInterruptedExecution(UUID executionId) {
        Execution execution = executionRepository.findByIdForUpdate(executionId).orElse(null);
        if (execution == null || (execution.getStatus() != ExecutionStatus.RUNNING
                && execution.getStatus() != ExecutionStatus.CANCELLING)) return;
        if (execution.getStatus() == ExecutionStatus.CANCELLING) {
            for (NodeExecution node : nodeExecutionRepository.findAllByExecution_Id(executionId)) {
                if (node.getStatus() == NodeExecutionStatus.RUNNING) {
                    node.transitionTo(
                            NodeExecutionStatus.FAILED,
                            node.getInputData(),
                            null,
                            "Active node was interrupted during application shutdown."
                    );
                } else if (node.getStatus() == NodeExecutionStatus.PENDING) {
                    node.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null);
                }
            }
            finishCancellation(execution);
            appendLifecycleLog(execution, ExecutionLogLevel.INFO, "Execution cancelled during application shutdown.", "EXECUTION_CANCELLED");
            return;
        }
        String error = "Execution was interrupted when the application stopped.";
        for (NodeExecution node : nodeExecutionRepository.findAllByExecution_Id(executionId)) {
            if (node.getStatus() == NodeExecutionStatus.RUNNING) {
                node.transitionTo(NodeExecutionStatus.FAILED, node.getInputData(), null, error);
            } else if (node.getStatus() == NodeExecutionStatus.PENDING) {
                node.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null);
            }
        }
        execution.transitionTo(ExecutionStatus.FAILED, error, "EXECUTION_INTERRUPTED");
        appendLifecycleLog(execution, ExecutionLogLevel.ERROR, error, "EXECUTION_INTERRUPTED");
    }

    @Transactional
    public void skipPendingNodes(UUID executionId) {
        nodeExecutionRepository.findAllByExecution_Id(executionId).stream()
                .filter(node -> node.getStatus() == NodeExecutionStatus.PENDING)
                .forEach(node -> node.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null));
    }

    private void reconcileNodesBeforeTerminal(UUID executionId, String error) {
        for (NodeExecution node : nodeExecutionRepository.findAllByExecution_Id(executionId)) {
            if (node.getStatus() == NodeExecutionStatus.RUNNING) {
                node.transitionTo(NodeExecutionStatus.FAILED, node.getInputData(), null, error);
            } else if (node.getStatus() == NodeExecutionStatus.PENDING) {
                node.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null);
            }
        }
    }

    private void finishCancellation(Execution execution) {
        execution.transitionTo(ExecutionStatus.CANCELLED, "Execution cancelled by request.", "EXECUTION_CANCELLED");
    }

    private void appendLifecycleLog(Execution execution, ExecutionLogLevel level, String message, String code) {
        executionLogRepository.save(new ExecutionLog(execution, null, level, message, detailsWithCode(code)));
    }

    private JsonNode detailsWithCode(String code) {
        return objectMapper.createObjectNode().put("errorCode", code);
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
