package com.flowforge.service;

import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.runtime.ExecutionDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExecutionRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionRecoveryService.class);

    private final ExecutionRepository executionRepository;
    private final ExecutionStateService stateService;
    private final ExecutionDispatcher dispatcher;
    private final ExecutionDispatchFailureRecorder dispatchFailureRecorder;
    private final Set<UUID> redispatching = ConcurrentHashMap.newKeySet();

    public ExecutionRecoveryService(
            ExecutionRepository executionRepository,
            ExecutionStateService stateService,
            ExecutionDispatcher dispatcher,
            ExecutionDispatchFailureRecorder dispatchFailureRecorder
    ) {
        this.executionRepository = executionRepository;
        this.stateService = stateService;
        this.dispatcher = dispatcher;
        this.dispatchFailureRecorder = dispatchFailureRecorder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        executionRepository.findAllByStatusIn(List.of(ExecutionStatus.RUNNING, ExecutionStatus.CANCELLING))
                .forEach(execution -> {
                    LOGGER.warn("Recovering interrupted workflow execution {}", execution.getId());
                    stateService.recoverInterruptedExecution(execution.getId());
                });
        redispatchQueuedExecutions();
    }

    @Scheduled(fixedDelayString = "${flowforge.execution.recovery-interval-ms:15000}")
    public void redispatchQueuedExecutions() {
        executionRepository.findAllByStatusIn(List.of(ExecutionStatus.QUEUED)).forEach(execution -> {
            UUID id = execution.getId();
            if (!redispatching.add(id)) return;
            try {
                LOGGER.info("Redispatching queued workflow execution {}", id);
                dispatcher.dispatch(id);
            } catch (RuntimeException exception) {
                redispatching.remove(id);
                LOGGER.error("Unable to redispatch workflow execution {} ({})", id, exception.getClass().getSimpleName());
                try {
                    dispatchFailureRecorder.recordFailure(id);
                } catch (RuntimeException recordingException) {
                    LOGGER.error("Unable to record redispatch failure for workflow execution {} ({})",
                            id, recordingException.getClass().getSimpleName());
                }
            }
        });
    }

    public void workerFinished(UUID executionId) {
        redispatching.remove(executionId);
    }
}
