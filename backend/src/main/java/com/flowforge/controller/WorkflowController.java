package com.flowforge.controller;

import com.flowforge.dto.workflow.CreateWorkflowRequest;
import com.flowforge.dto.workflow.UpdateWorkflowRequest;
import com.flowforge.dto.workflow.WorkflowResponse;
import com.flowforge.service.WorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/workflows")
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.createWorkflow(request));
    }

    @GetMapping("/workflows")
    public List<WorkflowResponse> listWorkflows() {
        return workflowService.listWorkflows();
    }

    @GetMapping("/workflows/{id}")
    public WorkflowResponse getWorkflow(@PathVariable UUID id) {
        return workflowService.getWorkflow(id);
    }

    @PutMapping("/workflows/{id}")
    public WorkflowResponse updateWorkflow(@PathVariable UUID id, @Valid @RequestBody UpdateWorkflowRequest request) {
        return workflowService.updateWorkflow(id, request);
    }

    @DeleteMapping("/workflows/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable UUID id) {
        workflowService.deleteWorkflow(id);
        return ResponseEntity.noContent().build();
    }

}
