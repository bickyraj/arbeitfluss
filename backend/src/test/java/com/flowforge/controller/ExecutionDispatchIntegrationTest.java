package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.executor.NodeExecutor;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.model.execution.NodeExecutionStatus;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.NodeExecutionRepository;
import com.flowforge.runtime.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ExecutionDispatchIntegrationTest.BlockingExecutorConfiguration.class)
class ExecutionDispatchIntegrationTest {
    private static volatile CountDownLatch executorStarted = new CountDownLatch(1);
    private static volatile CountDownLatch allowExecutorToFinish = new CountDownLatch(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private NodeExecutionRepository nodeExecutionRepository;

    @BeforeEach
    void resetExecutorLatches() {
        executorStarted = new CountDownLatch(1);
        allowExecutorToFinish = new CountDownLatch(1);
    }

    @Test
    void dispatchesAfterCommitTransitionsThroughRunningAndReachesTerminalState() throws Exception {
        UUID workflowId = createBlockingWorkflow(false);
        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{\"user\":\"Alex\"}}");

        try {
            assertThat(executorStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executionRepository.findById(executionId).orElseThrow().getStatus())
                    .isEqualTo(ExecutionStatus.RUNNING);
            var runningNode = nodeExecutionRepository
                    .findByExecution_IdAndWorkflowNodeId(executionId, "blocking")
                    .orElseThrow();
            assertThat(runningNode.getStatus()).isEqualTo(NodeExecutionStatus.RUNNING);
            assertThat(runningNode.getInputData().path("user").asText()).isEqualTo("Alex");
        } finally {
            allowExecutorToFinish.countDown();
        }

        awaitStatus(executionId, ExecutionStatus.SUCCEEDED);
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "blocking")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
    }

    @Test
    void cancellationWaitsForRunningNodeAndSkipsItsDownstreamWork() throws Exception {
        UUID workflowId = createBlockingWorkflow(true);
        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{}}");

        assertThat(executorStarted.await(5, TimeUnit.SECONDS)).isTrue();
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            var firstCancel = callers.submit(() -> cancelStatus(executionId));
            var secondCancel = callers.submit(() -> cancelStatus(executionId));
            assertThat(firstCancel.get(5, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(secondCancel.get(5, TimeUnit.SECONDS)).isEqualTo(202);
        }
        assertThat(executionRepository.findById(executionId).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.CANCELLING);
        mockMvc.perform(post("/api/executions/{id}/retry", executionId))
                .andExpect(status().isConflict());
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "blocking")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.RUNNING);

        allowExecutorToFinish.countDown();
        awaitStatus(executionId, ExecutionStatus.CANCELLED);
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "blocking")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "after")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.SKIPPED);
    }

    @Test
    void executionTimeoutIsFailedAndDoesNotScheduleDownstreamNodes() throws Exception {
        UUID workflowId = createBlockingWorkflow(true);
        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{},\"timeoutSeconds\":1}");

        try {
            assertThat(executorStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(1100);
        } finally {
            allowExecutorToFinish.countDown();
        }

        awaitStatus(executionId, ExecutionStatus.FAILED);
        assertThat(executionRepository.findById(executionId).orElseThrow().getErrorCode())
                .isEqualTo("EXECUTION_TIMEOUT");
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "blocking")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "after")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.SKIPPED);
    }

    private UUID createBlockingWorkflow(boolean includeDownstream) throws Exception {
        String nodes = """
                {"id":"trigger","type":"manual-trigger","configuration":{}},
                {"id":"blocking","type":"test-blocking","configuration":{}}
                """ + (includeDownstream
                ? ",{\"id\":\"after\",\"type\":\"log-message\",\"configuration\":{\"message\":\"must not run\"}}"
                : "");
        String connections = """
                {"id":"edge-1","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"blocking","targetHandle":"input"}
                """ + (includeDownstream
                ? ",{\"id\":\"edge-2\",\"sourceNodeId\":\"blocking\",\"sourceHandle\":\"output\",\"targetNodeId\":\"after\",\"targetHandle\":\"input\"}"
                : "");
        String definition = "{\"nodes\":[" + nodes + "],\"connections\":[" + connections + "]}";
        String workflowResponse = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("name", "Reliability Test")
                                .put("description", "Cancellation and timeout fixture")
                                .put("active", true)
                                .set("definition", objectMapper.readTree(definition))
                                .toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(workflowResponse).path("id").asText());
    }

    private UUID startExecution(UUID workflowId, String requestBody) throws Exception {
        String response = mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private int cancelStatus(UUID executionId) throws Exception {
        return mockMvc.perform(post("/api/executions/{id}/cancel", executionId))
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andReturn().getResponse().getStatus();
    }

    private void awaitStatus(UUID executionId, ExecutionStatus expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        ExecutionStatus status = ExecutionStatus.QUEUED;
        while (System.nanoTime() < deadline) {
            status = executionRepository.findById(executionId).orElseThrow().getStatus();
            if (status == expectedStatus) return;
            Thread.sleep(15);
        }
        assertThat(status).isEqualTo(expectedStatus);
    }

    @TestConfiguration
    static class BlockingExecutorConfiguration {
        @Bean
        NodeExecutor blockingTestNodeExecutor() {
            return new NodeExecutor() {
                @Override
                public String nodeType() {
                    return "test-blocking";
                }

                @Override
                public JsonNode execute(JsonNode input, ExecutionContext context) throws InterruptedException {
                    executorStarted.countDown();
                    if (!allowExecutorToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test executor was not released");
                    }
                    return input.deepCopy();
                }
            };
        }
    }
}
