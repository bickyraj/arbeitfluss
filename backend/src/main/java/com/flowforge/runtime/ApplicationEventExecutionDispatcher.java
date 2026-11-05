package com.flowforge.runtime;

import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ApplicationEventExecutionDispatcher implements ExecutionDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationEventExecutionDispatcher.class);

    private final ApplicationEventPublisher eventPublisher;

    public ApplicationEventExecutionDispatcher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void dispatch(UUID executionId) {
        LOGGER.info("Publishing queued workflow execution {}", executionId);
        eventPublisher.publishEvent(new ExecutionQueuedEvent(executionId));
    }
}
