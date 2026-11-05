package com.flowforge.controller;

import com.flowforge.dto.execution.ExecutionLogResponse;
import com.flowforge.dto.execution.ExecutionRequest;
import com.flowforge.dto.execution.ExecutionStartResponse;
import com.flowforge.dto.execution.NodeExecutionResponse;
import com.flowforge.dto.execution.ExecutionResponse;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.service.ExecutionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ExecutionController {
    private final ExecutionService workflowExecutionService;

    public ExecutionController(ExecutionService workflowExecutionService) {
        this.workflowExecutionService = workflowExecutionService;
    }

    @PostMapping("/workflows/{workflowId}/executions")
    public ResponseEntity<ExecutionStartResponse> startExecution(
            @PathVariable UUID workflowId,
            @RequestBody(required = false) ExecutionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(workflowExecutionService.startExecution(workflowId, request));
    }

    @GetMapping("/executions/{executionId}")
    public ExecutionResponse getExecution(@PathVariable UUID executionId) {
        return workflowExecutionService.getExecution(executionId);
    }

    @GetMapping("/workflows/{workflowId}/executions")
    public Page<ExecutionResponse> listExecutions(
            @PathVariable UUID workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidExecutionRequestException("Pagination requires page >= 0 and size between 1 and 100");
        }
        return workflowExecutionService.listExecutions(
                workflowId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/executions/{executionId}/nodes")
    public List<NodeExecutionResponse> listNodeExecutions(@PathVariable UUID executionId) {
        return workflowExecutionService.listNodeExecutions(executionId);
    }

    @GetMapping("/executions/{executionId}/logs")
    public List<ExecutionLogResponse> listExecutionLogs(@PathVariable UUID executionId) {
        return workflowExecutionService.listExecutionLogs(executionId);
    }
}
