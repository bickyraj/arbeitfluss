package com.flowforge.runtime;

import com.flowforge.service.ExecutionStateService;
import com.flowforge.service.ExecutionRecoveryService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class WorkflowExecutionWorkerTest {
    @Test
    void recordsAWorkerRuntimeFailureInsteadOfLeavingExecutionQueued() {
        WorkflowExecutionRuntime runtime = mock(WorkflowExecutionRuntime.class);
        ExecutionStateService stateService = mock(ExecutionStateService.class);
        ExecutionRecoveryService recoveryService = mock(ExecutionRecoveryService.class);
        WorkflowExecutionWorker worker = new WorkflowExecutionWorker(runtime, stateService, recoveryService);
        UUID executionId = UUID.randomUUID();
        doThrow(new IllegalStateException("internal failure"))
                .when(runtime).execute(executionId);

        worker.handle(new ExecutionQueuedEvent(executionId));

        verify(stateService).failExecution(executionId, "Workflow execution failed.");
    }
}
