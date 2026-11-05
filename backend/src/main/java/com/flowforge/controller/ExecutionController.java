package com.flowforge.controller;

import com.flowforge.dto.execution.ExecutionLogResponse;
import com.flowforge.dto.execution.ExecutionRequest;
import com.flowforge.dto.execution.ExecutionStartResponse;
import com.flowforge.dto.execution.NodeExecutionResponse;
import com.flowforge.dto.execution.ExecutionResponse;
import com.flowforge.dto.execution.ExecutionGraphResponse;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.service.ExecutionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

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
            @Valid @RequestBody(required = false) ExecutionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(workflowExecutionService.startExecution(workflowId, request));
    }

    @PostMapping("/executions/{executionId}/cancel")
    public ResponseEntity<ExecutionResponse> cancelExecution(@PathVariable UUID executionId) {
        ExecutionResponse response = workflowExecutionService.cancelExecution(executionId);
        HttpStatus status = response.status() == ExecutionStatus.CANCELLING
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/executions/{executionId}/retry")
    public ResponseEntity<ExecutionStartResponse> retryExecution(@PathVariable UUID executionId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(workflowExecutionService.retryExecution(executionId));
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

    @GetMapping("/executions")
    public Page<ExecutionResponse> listAllExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(defaultValue = "newest") String sort
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidExecutionRequestException("Pagination requires page >= 0 and size between 1 and 100");
        }
        Sort ordering = switch (sort) {
            case "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "duration-desc" -> Sort.by(Sort.Direction.DESC, "durationMs").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "status" -> Sort.by(Sort.Direction.ASC, "status").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> throw new InvalidExecutionRequestException("Unsupported execution sort option");
        };
        return workflowExecutionService.searchAllExecutions(
                status,
                search,
                PageRequest.of(page, size, ordering)
        );
    }

    @GetMapping("/executions/{executionId}/graph")
    public ExecutionGraphResponse getExecutionGraph(@PathVariable UUID executionId) {
        return workflowExecutionService.getExecutionGraph(executionId);
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
