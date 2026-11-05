package com.flowforge.service;

import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.runtime.ExecutionDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ExecutionRecoveryServiceTest {
    @Test
    void startupRepairsActiveExecutionsAndRedispatchesQueuedExecutionsOnlyOncePerWorker() {
        ExecutionRepository repository = mock(ExecutionRepository.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        ExecutionDispatcher dispatcher = mock(ExecutionDispatcher.class);
        ExecutionDispatchFailureRecorder failureRecorder = mock(ExecutionDispatchFailureRecorder.class);
        Execution running = mock(Execution.class);
        Execution queued = mock(Execution.class);
        UUID runningId = UUID.randomUUID();
        UUID queuedId = UUID.randomUUID();
        when(running.getId()).thenReturn(runningId);
        when(queued.getId()).thenReturn(queuedId);
        when(repository.findAllByStatusIn(anyList())).thenAnswer(invocation -> {
            List<ExecutionStatus> statuses = invocation.getArgument(0);
            return statuses.contains(ExecutionStatus.RUNNING) ? List.of(running) : List.of(queued);
        });

        ExecutionRecoveryService recovery = new ExecutionRecoveryService(
                repository, stateService, dispatcher, failureRecorder
        );
        recovery.recoverAfterStartup();
        recovery.redispatchQueuedExecutions();

        verify(stateService).recoverInterruptedExecution(runningId);
        verify(dispatcher, times(1)).dispatch(queuedId);

        recovery.workerFinished(queuedId);
        recovery.redispatchQueuedExecutions();
        verify(dispatcher, times(2)).dispatch(queuedId);
    }
}
