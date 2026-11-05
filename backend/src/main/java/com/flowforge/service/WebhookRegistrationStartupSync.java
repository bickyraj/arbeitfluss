package com.flowforge.service;

import com.flowforge.repository.WorkflowRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class WebhookRegistrationStartupSync {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookRegistrationStartupSync.class);
    private final WorkflowRepository workflowRepository;
    private final WebhookRegistrationService registrationService;

    public WebhookRegistrationStartupSync(
            WorkflowRepository workflowRepository,
            WebhookRegistrationService registrationService
    ) {
        this.workflowRepository = workflowRepository;
        this.registrationService = registrationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void synchronizeSavedWorkflows() {
        for (var workflowId : workflowRepository.findAllIdsForRegistrationSync()) {
            try {
                // Cross-bean invocation: each lock/read/reconcile/commit uses its own transaction.
                registrationService.synchronizeSavedWorkflow(workflowId);
            } catch (RuntimeException exception) {
                // Do not log exception messages or workflow JSON: legacy data can contain credentials.
                LOGGER.warn("Unable to reconcile webhook registrations for one saved workflow; continuing.");
            }
        }
    }
}
