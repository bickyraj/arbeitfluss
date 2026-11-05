package com.flowforge.service;

import com.flowforge.repository.WorkflowRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WebhookRegistrationStartupSync {
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
    @Transactional
    public void synchronizeSavedWorkflows() {
        workflowRepository.findAll().forEach(registrationService::synchronizeWorkflow);
    }
}
