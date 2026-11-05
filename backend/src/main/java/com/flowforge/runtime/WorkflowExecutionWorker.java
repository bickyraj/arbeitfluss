package com.flowforge.runtime;

import com.flowforge.service.ExecutionStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class WorkflowExecutionWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutionWorker.class);

    private final WorkflowExecutionRuntime runtime;
    private final ExecutionStateService stateService;

    public WorkflowExecutionWorker(WorkflowExecutionRuntime runtime, ExecutionStateService stateService) {
        this.runtime = runtime;
        this.stateService = stateService;
    }

    @Async("workflowExecutionExecutor")
    @EventListener
    public void handle(ExecutionQueuedEvent event) {
        LOGGER.info("Worker received workflow execution {}", event.executionId());
        try {
            runtime.execute(event.executionId());
        } catch (RuntimeException exception) {
            LOGGER.error("Workflow execution {} failed in the runtime coordinator ({})",
                    event.executionId(), exception.getClass().getSimpleName());
            try {
                stateService.failExecution(event.executionId(), "Workflow execution failed.");
            } catch (RuntimeException recordingException) {
                LOGGER.error("Unable to record worker failure for workflow execution {} ({})",
                        event.executionId(), recordingException.getClass().getSimpleName());
            }
        }
    }
}
