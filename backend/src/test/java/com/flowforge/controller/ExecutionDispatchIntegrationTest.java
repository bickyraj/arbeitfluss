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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        String workflowResponse = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Dispatch Test",
                                  "description":"Async execution integration fixture",
                                  "active":true,
                                  "definition":{
                                    "nodes":[
                                      {"id":"trigger","type":"manual-trigger","configuration":{}},
                                      {"id":"blocking","type":"test-blocking","configuration":{}}
                                    ],
                                    "connections":[
                                      {"id":"edge","sourceNodeId":"trigger","sourceHandle":"output",
                                       "targetNodeId":"blocking","targetHandle":"input"}
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID workflowId = UUID.fromString(objectMapper.readTree(workflowResponse).path("id").asText());

        String startResponse = mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerInput\":{\"user\":\"Alex\"}}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID executionId = UUID.fromString(objectMapper.readTree(startResponse).path("id").asText());

        try {
            assertThat(executionRepository.findById(executionId).orElseThrow().getStatus())
                    .isEqualTo(ExecutionStatus.QUEUED);
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

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ExecutionStatus status = ExecutionStatus.RUNNING;
        while (System.nanoTime() < deadline) {
            status = executionRepository.findById(executionId).orElseThrow().getStatus();
            if (status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.FAILED) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(status).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(executionId, "blocking")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.SUCCEEDED);
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
