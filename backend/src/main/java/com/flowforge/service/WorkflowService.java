package com.flowforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.ai.AiConfigurationPolicy;
import com.flowforge.dto.workflow.CreateWorkflowRequest;
import com.flowforge.dto.workflow.UpdateWorkflowRequest;
import com.flowforge.dto.workflow.WorkflowResponse;
import com.flowforge.entity.Workflow;
import com.flowforge.exception.ResourceNotFoundException;
import com.flowforge.repository.WorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;
    private final WebhookRegistrationService webhookRegistrationService;

    public WorkflowService(
            WorkflowRepository workflowRepository,
            ObjectMapper objectMapper,
            WebhookRegistrationService webhookRegistrationService
    ) {
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
        this.webhookRegistrationService = webhookRegistrationService;
    }

    @Transactional
    public WorkflowResponse createWorkflow(CreateWorkflowRequest request) {
        JsonNode definition = request.definition() != null ? request.definition() : defaultDefinition(request.name());
        AiConfigurationPolicy.rejectCredentialConfiguration(definition);

        Workflow workflow = new Workflow(
                request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                request.active() != null ? request.active() : true,
                webhookRegistrationService.sanitizeDefinition(definition)
        );
        Workflow savedWorkflow = workflowRepository.saveAndFlush(workflow);
        webhookRegistrationService.synchronizeWorkflow(savedWorkflow);
        return WorkflowResponse.fromEntity(savedWorkflow);
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> listWorkflows() {
        return workflowRepository.findAll().stream()
                .map(WorkflowResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(UUID id) {
        return WorkflowResponse.fromEntity(findWorkflowOrThrow(id));
    }

    @Transactional
    public WorkflowResponse updateWorkflow(UUID id, UpdateWorkflowRequest request) {
        Workflow workflow = findWorkflowForRegistrationUpdate(id);

        if (request.name() != null && !request.name().isBlank()) {
            workflow.setName(request.name().trim());
        }
        if (request.description() != null) {
            workflow.setDescription(request.description().trim());
        }
        if (request.active() != null) {
            workflow.setActive(request.active());
        }
        if (request.definition() != null) {
            workflow.setDefinition(request.definition());
        }

        AiConfigurationPolicy.rejectCredentialConfiguration(workflow.getDefinition());
        workflow.setDefinition(webhookRegistrationService.sanitizeDefinition(workflow.getDefinition()));
        Workflow savedWorkflow = workflowRepository.saveAndFlush(workflow);
        webhookRegistrationService.synchronizeWorkflow(savedWorkflow);
        return WorkflowResponse.fromEntity(savedWorkflow);
    }

    @Transactional
    public void deleteWorkflow(UUID id) {
        Workflow workflow = findWorkflowForRegistrationUpdate(id);
        webhookRegistrationService.deleteWorkflowWebhooks(id);
        workflowRepository.delete(workflow);
    }

    private Workflow findWorkflowForRegistrationUpdate(UUID id) {
        return workflowRepository.findByIdForRegistrationUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + id));
    }

    private Workflow findWorkflowOrThrow(UUID id) {
        return workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + id));
    }

    private JsonNode defaultDefinition(String workflowName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workflowId", UUID.randomUUID().toString());
        node.put("workflowName", workflowName == null ? "New Workflow" : workflowName);
        node.putArray("nodes");
        node.putArray("connections");
        node.putNull("selectedNodeId");
        node.putNull("selectedConnectionId");
        node.put("isDirty", false);
        return node;
    }
}
