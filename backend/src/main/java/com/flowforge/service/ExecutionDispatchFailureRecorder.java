package com.flowforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.repository.ExecutionLogRepository;
import com.flowforge.repository.ExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ExecutionDispatchFailureRecorder {
    private static final String SAFE_ERROR = "Execution could not be dispatched.";

    private final ExecutionRepository executionRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final ObjectMapper objectMapper;

    public ExecutionDispatchFailureRecorder(
            ExecutionRepository executionRepository,
            ExecutionLogRepository executionLogRepository,
            ObjectMapper objectMapper
    ) {
        this.executionRepository = executionRepository;
        this.executionLogRepository = executionLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID executionId) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            if (execution.getStatus() == ExecutionStatus.QUEUED) {
                execution.transitionTo(ExecutionStatus.FAILED, SAFE_ERROR);
                executionLogRepository.save(new ExecutionLog(
                        execution, null, ExecutionLogLevel.ERROR, SAFE_ERROR, objectMapper.createObjectNode()
                ));
            }
        });
    }
}
