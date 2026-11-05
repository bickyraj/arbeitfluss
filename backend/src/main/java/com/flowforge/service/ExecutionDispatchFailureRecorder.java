package com.flowforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.repository.ExecutionLogRepository;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.NodeExecutionRepository;
import com.flowforge.model.execution.NodeExecutionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ExecutionDispatchFailureRecorder {
    private static final String SAFE_ERROR = "Execution could not be dispatched.";

    private final ExecutionRepository executionRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final ObjectMapper objectMapper;

    public ExecutionDispatchFailureRecorder(
            ExecutionRepository executionRepository,
            ExecutionLogRepository executionLogRepository,
            NodeExecutionRepository nodeExecutionRepository,
            ObjectMapper objectMapper
    ) {
        this.executionRepository = executionRepository;
        this.executionLogRepository = executionLogRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID executionId) {
        executionRepository.findByIdForUpdate(executionId).ifPresent(execution -> {
            if (execution.getStatus() == ExecutionStatus.QUEUED) {
                execution.transitionTo(ExecutionStatus.FAILED, SAFE_ERROR, "EXECUTION_DISPATCH_FAILED");
                nodeExecutionRepository.findAllByExecution_Id(executionId).stream()
                        .filter(node -> node.getStatus() == NodeExecutionStatus.PENDING)
                        .forEach(node -> node.transitionTo(NodeExecutionStatus.SKIPPED, null, null, null));
                executionLogRepository.save(new ExecutionLog(
                        execution, null, ExecutionLogLevel.ERROR, SAFE_ERROR,
                        objectMapper.createObjectNode().put("errorCode", "EXECUTION_DISPATCH_FAILED")
                ));
            }
        });
    }
}
