package com.flowforge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.entity.Workflow;
import com.flowforge.entity.WebhookRegistration;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.exception.WebhookRequestRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import com.flowforge.exception.ResourceNotFoundException;
import com.flowforge.runtime.ExecutionDispatcher;
import com.flowforge.dto.execution.ExecutionLogResponse;
import com.flowforge.dto.execution.ExecutionRequest;
import com.flowforge.dto.execution.ExecutionStartResponse;
import com.flowforge.dto.execution.NodeExecutionResponse;
import com.flowforge.dto.execution.ExecutionResponse;
import com.flowforge.dto.execution.ExecutionGraphResponse;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.entity.NodeExecution;
import com.flowforge.entity.Execution;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.exception.ExecutionConflictException;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.model.execution.ExecutionTriggerType;
import com.flowforge.model.execution.WorkflowSnapshot;
import com.flowforge.runtime.WorkflowGraphPlan;
import com.flowforge.repository.ExecutionLogRepository;
import com.flowforge.repository.NodeExecutionRepository;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.validation.WorkflowGraphValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class ExecutionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionService.class);
    private static final int MAX_TRIGGER_INPUT_BYTES = 1_048_576;
    private static final int MAX_DEFINITION_SNAPSHOT_BYTES = 5_242_880;

    private final WorkflowRepository workflowRepository;
    private final ExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final ObjectMapper objectMapper;
    private final ExecutionDispatcher executionDispatcher;
    private final ExecutionDispatchFailureRecorder dispatchFailureRecorder;
    private final WorkflowGraphValidator graphValidator;
    private final ExecutionStateService stateService;
    private final WebhookRegistrationRepository webhookRegistrationRepository;
    private final WebhookRegistrationService webhookRegistrationService;
    private final WebhookIngressSettings webhookIngressSettings;

    public ExecutionService(
            WorkflowRepository workflowRepository,
            ExecutionRepository executionRepository,
            NodeExecutionRepository nodeExecutionRepository,
            ExecutionLogRepository executionLogRepository,
            ObjectMapper objectMapper,
            ExecutionDispatcher executionDispatcher,
            ExecutionDispatchFailureRecorder dispatchFailureRecorder,
            WorkflowGraphValidator graphValidator,
            ExecutionStateService stateService,
            WebhookRegistrationRepository webhookRegistrationRepository,
            WebhookRegistrationService webhookRegistrationService,
            WebhookIngressSettings webhookIngressSettings
    ) {
        this.workflowRepository = workflowRepository;
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.executionLogRepository = executionLogRepository;
        this.objectMapper = objectMapper;
        this.executionDispatcher = executionDispatcher;
        this.dispatchFailureRecorder = dispatchFailureRecorder;
        this.graphValidator = graphValidator;
        this.stateService = stateService;
        this.webhookRegistrationRepository = webhookRegistrationRepository;
        this.webhookRegistrationService = webhookRegistrationService;
        this.webhookIngressSettings = webhookIngressSettings;
    }

    @Transactional
    public ExecutionStartResponse startExecution(UUID workflowId, ExecutionRequest request) {
        return startExecution(workflowId, request, ExecutionTriggerType.MANUAL, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionStartResponse startWebhookExecution(
            String webhookId, String method, String presentedSecret, JsonNode triggerInput
    ) {
        if (!webhookIngressSettings.isEnabled() || webhookId == null || !webhookId.matches("[A-Za-z0-9_-]{43}")) {
            throw new WebhookRequestRejectedException(HttpStatus.NOT_FOUND);
        }
        // Scalar discovery must not preload a stale registration/workflow into the persistence context.
        UUID workflowId = webhookRegistrationRepository.findWorkflowIdForIngress(webhookId)
                .orElseThrow(() -> new WebhookRequestRejectedException(HttpStatus.NOT_FOUND));
        Workflow workflow = workflowRepository.findByIdForRegistrationUpdate(workflowId)
                .orElseThrow(() -> new WebhookRequestRejectedException(HttpStatus.NOT_FOUND));
        WebhookRegistration registration = webhookRegistrationRepository.findByWebhookId(webhookId)
                .orElseThrow(() -> new WebhookRequestRejectedException(HttpStatus.NOT_FOUND));
        webhookRegistrationService.authorizeIngress(registration, workflow, method, presentedSecret);
        return startExecution(workflowId, new ExecutionRequest(triggerInput, null),
                ExecutionTriggerType.WEBHOOK, registration.getNodeId());
    }

    private ExecutionStartResponse startExecution(
            UUID workflowId,
            ExecutionRequest request,
            ExecutionTriggerType triggerType,
            String expectedWebhookNodeId
    ) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found: " + workflowId));
        if (triggerType == ExecutionTriggerType.WEBHOOK && !workflow.isActive()) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        JsonNode triggerInput = request == null || request.triggerInput() == null
                ? objectMapper.createObjectNode()
                : request.triggerInput().deepCopy();
        int timeoutSeconds = request == null || request.timeoutSeconds() == null ? 300 : request.timeoutSeconds();
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new InvalidExecutionRequestException("Execution timeout must be between 1 and 3600 seconds");
        }
        validatePayloadSize(triggerInput, MAX_TRIGGER_INPUT_BYTES, "Trigger input");

        WorkflowSnapshot snapshot = new WorkflowSnapshot(workflow.getId(), workflow.getName(), workflow.getDefinition());
        JsonNode definition = snapshot.definition();
        validatePayloadSize(definition, MAX_DEFINITION_SNAPSHOT_BYTES, "Workflow definition");
        WorkflowGraphPlan graph = graphValidator.validate(definition);
        if (expectedWebhookNodeId != null && !expectedWebhookNodeId.equals(graph.triggerNodeId())) {
            throw new WebhookRequestRejectedException(HttpStatus.NOT_FOUND);
        }
        String expectedTriggerNodeType = triggerType == ExecutionTriggerType.WEBHOOK
                ? "webhook-trigger"
                : "manual-trigger";
        if (!expectedTriggerNodeType.equals(graph.triggerNodeType())) {
            throw new InvalidExecutionRequestException(
                    "Execution trigger type does not match the workflow's root trigger"
            );
        }

        Execution execution = new Execution(
                workflow,
                snapshot.workflowName(),
                snapshot.definition(),
                triggerType,
                triggerInput,
                timeoutSeconds,
                null
        );
        return persistAndDispatch(execution, graph);
    }

    @Transactional
    public ExecutionStartResponse retryExecution(UUID executionId) {
        Execution original = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
        if (original.getStatus() != ExecutionStatus.FAILED) {
            throw new ExecutionConflictException("Only failed executions can be retried.");
        }
        JsonNode definition = original.getWorkflowDefinition();
        WorkflowGraphPlan graph = graphValidator.validate(definition);
        Execution retry = new Execution(
                original.getWorkflow(),
                original.getWorkflowName(),
                definition,
                original.getTriggerType(),
                original.getTriggerInput(),
                original.getTimeoutSeconds(),
                original
        );
        return persistAndDispatch(retry, graph);
    }

    @Transactional
    public ExecutionResponse cancelExecution(UUID executionId) {
        stateService.requestCancellation(executionId);
        return getExecution(executionId);
    }

    private ExecutionStartResponse persistAndDispatch(Execution newExecution, WorkflowGraphPlan graph) {
        Execution execution = executionRepository.saveAndFlush(newExecution);

        List<NodeExecution> nodeExecutions = graph.executionOrder().stream()
                .map(node -> new NodeExecution(
                        execution,
                        node.id(),
                        node.type()
                ))
                .toList();
        nodeExecutionRepository.saveAll(nodeExecutions);

        ObjectNode details = objectMapper.createObjectNode();
        details.put("triggerType", execution.getTriggerType().name());
        details.put("nodeCount", nodeExecutions.size());
        details.put("timeoutSeconds", execution.getTimeoutSeconds());
        if (execution.getRetryOfExecutionId() != null) {
            details.put("retryOfExecutionId", execution.getRetryOfExecutionId().toString());
        }
        executionLogRepository.save(new ExecutionLog(
                execution,
                null,
                ExecutionLogLevel.INFO,
                "Execution queued with an immutable workflow snapshot.",
                details
        ));

        UUID executionId = execution.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    LOGGER.info("Dispatching workflow execution {}", executionId);
                    executionDispatcher.dispatch(executionId);
                } catch (RuntimeException exception) {
                    LOGGER.error("Unable to dispatch workflow execution {} ({})",
                            executionId, exception.getClass().getSimpleName());
                    try {
                        dispatchFailureRecorder.recordFailure(executionId);
                    } catch (RuntimeException recordingException) {
                        LOGGER.error("Unable to record dispatch failure for workflow execution {} ({})",
                                executionId, recordingException.getClass().getSimpleName());
                    }
                }
            }
        });

        return new ExecutionStartResponse(execution.getId(), execution.getStatus(), execution.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public ExecutionResponse getExecution(UUID executionId) {
        return ExecutionResponse.fromEntity(findExecution(executionId));
    }

    @Transactional(readOnly = true)
    public Page<ExecutionResponse> listExecutions(UUID workflowId, Pageable pageable) {
        if (!workflowRepository.existsById(workflowId)) {
            throw new ResourceNotFoundException("Workflow not found: " + workflowId);
        }
        return executionRepository.findAllByWorkflow_IdOrderByCreatedAtDesc(workflowId, pageable)
                .map(ExecutionResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ExecutionResponse> searchAllExecutions(
            ExecutionStatus status,
            String search,
            Pageable pageable
    ) {
        // Always bind a String: PostgreSQL cannot apply lower() to an untyped null bound as bytea.
        String searchPattern = search == null || search.isBlank() ? "%" : "%" + search.trim() + "%";
        return executionRepository.searchAll(status, searchPattern, pageable)
                .map(ExecutionResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public ExecutionGraphResponse getExecutionGraph(UUID executionId) {
        return ExecutionGraphResponse.fromDefinition(findExecution(executionId).getWorkflowDefinition());
    }

    @Transactional(readOnly = true)
    public List<NodeExecutionResponse> listNodeExecutions(UUID executionId) {
        findExecution(executionId);
        return nodeExecutionRepository.findAllByExecution_IdOrderByCreatedAtAscWorkflowNodeIdAsc(executionId)
                .stream()
                .map(NodeExecutionResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExecutionLogResponse> listExecutionLogs(UUID executionId) {
        findExecution(executionId);
        return executionLogRepository.findAllByExecution_IdOrderByCreatedAtAscIdAsc(executionId)
                .stream()
                .map(ExecutionLogResponse::fromEntity)
                .toList();
    }

    private Execution findExecution(UUID executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found: " + executionId));
    }

    private void validatePayloadSize(JsonNode payload, int maxBytes, String label) {
        try {
            int payloadBytes = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes > maxBytes) {
                throw new InvalidExecutionRequestException(label + " exceeds the maximum allowed size");
            }
        } catch (JsonProcessingException exception) {
            throw new InvalidExecutionRequestException(label + " is not valid JSON");
        }
    }
}
