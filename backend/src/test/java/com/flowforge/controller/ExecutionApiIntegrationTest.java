package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.repository.ExecutionLogRepository;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.model.execution.NodeExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionApiIntegrationTest {

    private static final String DEFINITION = """
            {
              "workflowId":"editor-wf-1",
              "workflowName":"Execution Fixture",
              "nodes":[
                {"id":"node-trigger","type":"manual-trigger","name":"Manual Trigger","position":{"x":1,"y":2},"configuration":{"triggerInfo":"Manual"}},
                {"id":"node-output","type":"log-message","name":"Output","position":{"x":3,"y":4},"configuration":{"message":"done"}}
              ],
              "connections":[
                {"id":"edge-1","sourceNodeId":"node-trigger","sourceHandle":"output","targetNodeId":"node-output","targetHandle":"input"}
              ],
              "selectedNodeId":null,
              "selectedConnectionId":null,
              "isDirty":false
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionLogRepository executionLogRepository;

    @Test
    void executesSnapshotAndExposesExecutionHistoryNodesAndChronologicalLogs() throws Exception {
        UUID workflowId = createWorkflow();
        String response = mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerInput\":{\"customerId\":\"c-123\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID executionId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        JsonNode beforeEdit = executionRepository.findById(executionId).orElseThrow().getWorkflowDefinition();
        assertThat(beforeEdit.path("nodes").get(0).path("id").asText()).isEqualTo("node-trigger");
        assertThat(beforeEdit.path("connections").get(0).path("sourceHandle").asText()).isEqualTo("output");

        mockMvc.perform(put("/api/workflows/{id}", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Edited after run\",\"definition\":{\"nodes\":[],\"connections\":[]}}"))
                .andExpect(status().isOk());
        assertThat(executionRepository.findById(executionId).orElseThrow().getWorkflowDefinition()).isEqualTo(beforeEdit);
        awaitStatus(executionId, ExecutionStatus.SUCCEEDED);

        mockMvc.perform(get("/api/executions/{id}", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId.toString()))
                .andExpect(jsonPath("$.workflowName").value("Execution Fixture"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.workflowDefinition").doesNotExist());

        mockMvc.perform(get("/api/workflows/{id}/executions", workflowId)
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(executionId.toString()));

        mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowNodeId").value("node-trigger"))
                .andExpect(jsonPath("$[0].nodeType").value("manual-trigger"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[1].workflowNodeId").value("node-output"))
                .andExpect(jsonPath("$[1].status").value("SUCCEEDED"));

        Execution execution = executionRepository.findById(executionId).orElseThrow();
        executionLogRepository.saveAndFlush(new ExecutionLog(
                execution, null, ExecutionLogLevel.INFO, "Second ordered log", objectMapper.createObjectNode()
        ));
        mockMvc.perform(get("/api/executions/{id}/logs", executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Execution queued with an immutable workflow snapshot."))
                .andExpect(jsonPath("$[1].message").value("done"));
    }

    @Test
    void rejectsUnknownWorkflowAndInvalidConnectionReferences() throws Exception {
        mockMvc.perform(post("/api/workflows/{id}/executions", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        UUID workflowId = createWorkflow();
        mockMvc.perform(put("/api/workflows/{id}", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definition":{"nodes":[{"id":"only","type":"manual-trigger"}],
                                "connections":[{"id":"broken","sourceNodeId":"missing","sourceHandle":"output","targetNodeId":"only","targetHandle":"input"}]}}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workflows/{id}/executions", workflowId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Connection 'broken' references missing node 'missing'"));
    }

    @Test
    void executesConnectedNodesInGraphOrderAndPassesData() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"logger","type":"log-message","configuration":{"message":"Done"}},
                    {"id":"setter","type":"set-data","configuration":{"key":"account_id","value":"acct_123"}},
                    {"id":"trigger","type":"manual-trigger","configuration":{}}
                  ],
                  "connections":[
                    {"id":"to-setter","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"setter","targetHandle":"input"},
                    {"id":"to-logger","sourceNodeId":"setter","sourceHandle":"output","targetNodeId":"logger","targetHandle":"input"}
                  ]
                }
                """);

        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{\"customerId\":\"c-123\"}}");
        awaitStatus(executionId, ExecutionStatus.SUCCEEDED);

        JsonNode nodeResponse = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andReturn().getResponse().getContentAsString());
        List<JsonNode> nodes = new java.util.ArrayList<>();
        nodeResponse.forEach(nodes::add);
        assertThat(nodes).hasSize(3);
        assertThat(nodes).extracting(node -> node.path("workflowNodeId").asText())
                .containsExactly("trigger", "setter", "logger");
        assertThat(nodes.get(1).path("inputData").path("customerId").asText()).isEqualTo("c-123");
        assertThat(nodes.get(1).path("outputData").path("account_id").asText()).isEqualTo("acct_123");
        assertThat(nodes.get(2).path("outputData").path("customerId").asText()).isEqualTo("c-123");
        assertThat(nodes).allSatisfy(node ->
                assertThat(node.path("status").asText()).isEqualTo(NodeExecutionStatus.SUCCEEDED.name()));
    }

    @Test
    void executesFanOutBranchesWithoutSharingMutatedOutput() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"branch-b","type":"set-data","configuration":{"key":"branch_b","value":2}},
                    {"id":"branch-a","type":"set-data","configuration":{"key":"branch_a","value":1}},
                    {"id":"trigger","type":"manual-trigger","configuration":{}}
                  ],
                  "connections":[
                    {"id":"edge-a","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"branch-a","targetHandle":"input"},
                    {"id":"edge-b","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"branch-b","targetHandle":"input"}
                  ]
                }
                """);
        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{\"shared\":true}}");
        awaitStatus(executionId, ExecutionStatus.SUCCEEDED);

        JsonNode nodes = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andReturn().getResponse().getContentAsString());
        JsonNode branchA = findNode(nodes, "branch-a");
        JsonNode branchB = findNode(nodes, "branch-b");
        assertThat(branchA.path("outputData").path("shared").asBoolean()).isTrue();
        assertThat(branchA.path("outputData").path("branch_a").asInt()).isEqualTo(1);
        assertThat(branchA.path("outputData").has("branch_b")).isFalse();
        assertThat(branchB.path("outputData").path("branch_b").asInt()).isEqualTo(2);
        assertThat(branchB.path("outputData").has("branch_a")).isFalse();
    }

    @Test
    void failsNodeAndSkipsItsDownstreamNodes() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"trigger","type":"manual-trigger","configuration":{}},
                    {"id":"broken-set","type":"set-data","configuration":{}},
                    {"id":"never-run","type":"log-message","configuration":{"message":"not run"}}
                  ],
                  "connections":[
                    {"id":"edge-1","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"broken-set","targetHandle":"input"},
                    {"id":"edge-2","sourceNodeId":"broken-set","sourceHandle":"output","targetNodeId":"never-run","targetHandle":"input"}
                  ]
                }
                """);
        UUID executionId = startExecution(workflowId, "{}");
        awaitStatus(executionId, ExecutionStatus.FAILED);

        JsonNode nodes = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andReturn().getResponse().getContentAsString());
        assertThat(findNode(nodes, "broken-set").path("status").asText()).isEqualTo("FAILED");
        assertThat(findNode(nodes, "broken-set").path("errorMessage").asText())
                .contains("Set Data requires a non-empty key and a value");
        assertThat(findNode(nodes, "never-run").path("status").asText()).isEqualTo("SKIPPED");
    }

    private UUID createWorkflow() throws Exception {
        return createWorkflow(DEFINITION);
    }

    private UUID createWorkflow(String definition) throws Exception {
        String response = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("name", "Execution Fixture")
                                .put("description", "Fixture")
                                .put("active", true)
                                .set("definition", objectMapper.readTree(definition))
                                .toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID startExecution(UUID workflowId, String requestBody) throws Exception {
        String response = mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void awaitStatus(UUID executionId, ExecutionStatus expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        ExecutionStatus status = ExecutionStatus.QUEUED;
        while (System.currentTimeMillis() < deadline) {
            JsonNode response = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}", executionId))
                    .andReturn().getResponse().getContentAsString());
            status = ExecutionStatus.valueOf(response.path("status").asText());
            if (status == expectedStatus) {
                return;
            }
            if (status == ExecutionStatus.FAILED || status == ExecutionStatus.SUCCEEDED) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(status).isEqualTo(expectedStatus);
    }

    private JsonNode findNode(JsonNode nodes, String workflowNodeId) {
        for (JsonNode node : nodes) {
            if (workflowNodeId.equals(node.path("workflowNodeId").asText())) {
                return node;
            }
        }
        throw new AssertionError("Node not found: " + workflowNodeId);
    }
}
