package com.flowforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    public WorkflowService(WorkflowRepository workflowRepository, ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowResponse createWorkflow(CreateWorkflowRequest request) {
        JsonNode definition = request.definition() != null ? request.definition() : defaultDefinition(request.name());

        Workflow workflow = new Workflow(
                request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                request.active() != null ? request.active() : true,
                definition
        );

        return WorkflowResponse.fromEntity(workflowRepository.saveAndFlush(workflow));
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
        Workflow workflow = findWorkflowOrThrow(id);

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

        return WorkflowResponse.fromEntity(workflowRepository.saveAndFlush(workflow));
    }

    @Transactional
    public void deleteWorkflow(UUID id) {
        Workflow workflow = findWorkflowOrThrow(id);
        workflowRepository.delete(workflow);
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
