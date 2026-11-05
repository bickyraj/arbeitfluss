package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.ExecutionLog;
import com.flowforge.entity.Execution;
import com.flowforge.entity.NodeExecution;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.repository.ExecutionLogRepository;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.NodeExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.service.ExecutionStateService;
import com.flowforge.service.ExecutionRecoveryService;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.model.execution.NodeExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MockMvcAdminAuthConfiguration.class)
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
    @Autowired private NodeExecutionRepository nodeExecutionRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ExecutionStateService executionStateService;
    @Autowired private ExecutionRecoveryService executionRecoveryService;

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
    void retriesFailedExecutionFromImmutableSnapshotInputAndTimeout() throws Exception {
        String definition = """
                {
                  "nodes":[
                    {"id":"trigger","type":"manual-trigger","configuration":{}},
                    {"id":"unsupported","type":"set-data","configuration":{}}
                  ],
                  "connections":[
                    {"id":"edge","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"unsupported","targetHandle":"input"}
                  ]
                }
                """;
        UUID workflowId = createWorkflow(definition);
        UUID originalId = startExecution(
                workflowId,
                "{\"triggerInput\":{\"request\":\"preserve-me\"},\"timeoutSeconds\":42}"
        );
        awaitStatus(originalId, ExecutionStatus.FAILED);

        Execution originalBefore = executionRepository.findById(originalId).orElseThrow();
        String originalError = originalBefore.getErrorMessage();
        long originalLogCount = executionLogRepository.findAllByExecution_IdOrderByCreatedAtAscIdAsc(originalId).size();

        mockMvc.perform(put("/api/workflows/{id}", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Edited workflow","definition":{"nodes":[{"id":"trigger","type":"manual-trigger","configuration":{}}],"connections":[]}}
                                """))
                .andExpect(status().isOk());

        String retryResponse = mockMvc.perform(post("/api/executions/{id}/retry", originalId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID retryId = UUID.fromString(objectMapper.readTree(retryResponse).path("id").asText());
        awaitStatus(retryId, ExecutionStatus.FAILED);

        Execution retry = executionRepository.findById(retryId).orElseThrow();
        assertThat(retry.getRetryOfExecutionId()).isEqualTo(originalId);
        assertThat(retry.getTimeoutSeconds()).isEqualTo(42);
        assertThat(retry.getWorkflowName()).isEqualTo(originalBefore.getWorkflowName());
        assertThat(retry.getWorkflowDefinition()).isEqualTo(originalBefore.getWorkflowDefinition());
        assertThat(retry.getTriggerInput()).isEqualTo(originalBefore.getTriggerInput());
        assertThat(executionRepository.findById(originalId).orElseThrow().getErrorMessage()).isEqualTo(originalError);
        assertThat(executionLogRepository.findAllByExecution_IdOrderByCreatedAtAscIdAsc(originalId))
                .hasSize((int) originalLogCount);

        mockMvc.perform(get("/api/executions/{id}/nodes", retryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inputData.request").value("preserve-me"));
        String history = mockMvc.perform(get("/api/executions")
                        .param("page", "0")
                        .param("size", "10")
                        .param("search", "Execution Fixture"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode historyContent = objectMapper.readTree(history).path("content");
        assertThat(historyContent.toString()).contains(originalId.toString(), retryId.toString());
    }

    @Test
    void rejectsExecutionTimeoutOutsideTheSupportedRange() throws Exception {
        UUID workflowId = createWorkflow();
        mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutSeconds\":3601}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recoveryMarksInterruptedNodeFailedAndPreservesEarlierOutputs() throws Exception {
        UUID workflowId = createWorkflow();
        var workflow = workflowRepository.findById(workflowId).orElseThrow();
        Execution interrupted = executionRepository.saveAndFlush(new Execution(
                workflow,
                "Execution Fixture",
                objectMapper.readTree(DEFINITION),
                com.flowforge.model.execution.ExecutionTriggerType.MANUAL,
                objectMapper.createObjectNode(),
                300,
                null
        ));
        interrupted.transitionTo(ExecutionStatus.RUNNING, null);
        executionRepository.saveAndFlush(interrupted);
        NodeExecution completed = new NodeExecution(interrupted, "node-trigger", "manual-trigger");
        completed.transitionTo(NodeExecutionStatus.RUNNING, null, null, null);
        completed.transitionTo(NodeExecutionStatus.SUCCEEDED, null, objectMapper.createObjectNode().put("ok", true), null);
        NodeExecution active = new NodeExecution(interrupted, "node-output", "log-message");
        active.transitionTo(NodeExecutionStatus.RUNNING, objectMapper.createObjectNode().put("input", 1), null, null);
        nodeExecutionRepository.saveAllAndFlush(List.of(completed, active));

        executionStateService.recoverInterruptedExecution(interrupted.getId());

        Execution recovered = executionRepository.findById(interrupted.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(recovered.getErrorCode()).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(interrupted.getId(), "node-trigger")
                .orElseThrow().getOutputData().path("ok").asBoolean()).isTrue();
        assertThat(nodeExecutionRepository.findByExecution_IdAndWorkflowNodeId(interrupted.getId(), "node-output")
                .orElseThrow().getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    }

    @Test
    void queuedCancellationIsTerminalAndSkipsEveryPendingNode() throws Exception {
        UUID workflowId = createWorkflow();
        Execution queued = executionRepository.saveAndFlush(new Execution(
                workflowRepository.findById(workflowId).orElseThrow(),
                "Execution Fixture",
                objectMapper.readTree(DEFINITION),
                com.flowforge.model.execution.ExecutionTriggerType.MANUAL,
                objectMapper.createObjectNode(),
                300,
                null
        ));
        nodeExecutionRepository.saveAllAndFlush(List.of(
                new NodeExecution(queued, "node-trigger", "manual-trigger"),
                new NodeExecution(queued, "node-output", "log-message")
        ));

        mockMvc.perform(post("/api/executions/{id}/cancel", queued.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(nodeExecutionRepository.findAllByExecution_Id(queued.getId()))
                .allSatisfy(node -> assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.SKIPPED));
        mockMvc.perform(post("/api/executions/{id}/cancel", queued.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void recoveryRedispatchesPersistedQueuedExecution() throws Exception {
        UUID workflowId = createWorkflow();
        Execution queued = executionRepository.saveAndFlush(new Execution(
                workflowRepository.findById(workflowId).orElseThrow(),
                "Execution Fixture",
                objectMapper.readTree(DEFINITION),
                com.flowforge.model.execution.ExecutionTriggerType.MANUAL,
                objectMapper.createObjectNode(),
                300,
                null
        ));
        nodeExecutionRepository.saveAllAndFlush(List.of(
                new NodeExecution(queued, "node-trigger", "manual-trigger"),
                new NodeExecution(queued, "node-output", "log-message")
        ));

        executionRecoveryService.redispatchQueuedExecutions();

        awaitStatus(queued.getId(), ExecutionStatus.SUCCEEDED);
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
    void selectsConditionalBranchesFansOutAndRejoinsActiveInputsOnce() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"trigger","type":"manual-trigger"},
                    {"id":"condition","type":"if-condition","configuration":{"field":"age","operator":"greater than or equal","value":18}},
                    {"id":"common","type":"set-data","configuration":{"key":"common","value":true}},
                    {"id":"true-a","type":"set-data","configuration":{"key":"true_a","value":1}},
                    {"id":"true-b","type":"set-data","configuration":{"key":"true_b","value":2}},
                    {"id":"false-path","type":"set-data","configuration":{"key":"false_path","value":3}},
                    {"id":"join","type":"set-data","configuration":{"key":"joined","value":true}},
                    {"id":"after-join","type":"log-message","configuration":{"message":"Joined"}}
                  ],
                  "connections":[
                    {"id":"start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"condition","targetHandle":"input"},
                    {"id":"common-edge","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"common","targetHandle":"input"},
                    {"id":"true-a-edge","sourceNodeId":"condition","sourceHandle":"true","targetNodeId":"true-a","targetHandle":"input"},
                    {"id":"true-b-edge","sourceNodeId":"condition","sourceHandle":"true","targetNodeId":"true-b","targetHandle":"input"},
                    {"id":"false-edge","sourceNodeId":"condition","sourceHandle":"false","targetNodeId":"false-path","targetHandle":"input"},
                    {"id":"join-a","sourceNodeId":"true-a","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"join-b","sourceNodeId":"true-b","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"join-false","sourceNodeId":"false-path","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"join-common","sourceNodeId":"common","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"after","sourceNodeId":"join","sourceHandle":"output","targetNodeId":"after-join","targetHandle":"input"}
                  ]
                }
                """);

        UUID trueExecutionId = startExecution(workflowId, "{\"triggerInput\":{\"age\":20}}");
        awaitStatus(trueExecutionId, ExecutionStatus.SUCCEEDED);
        JsonNode trueNodes = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", trueExecutionId))
                .andReturn().getResponse().getContentAsString());
        assertThat(findNode(trueNodes, "condition").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(trueNodes, "true-a").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(trueNodes, "true-b").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(trueNodes, "false-path").path("status").asText()).isEqualTo("SKIPPED");
        JsonNode trueJoinInput = findNode(trueNodes, "join").path("inputData").path("inputs");
        assertThat(trueJoinInput.size()).isEqualTo(3);
        assertThat(trueJoinInput.has("true-a")).isTrue();
        assertThat(trueJoinInput.has("true-b")).isTrue();
        assertThat(trueJoinInput.has("common")).isTrue();
        assertThat(findNode(trueNodes, "join").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(trueNodes).filteredOn(node -> node.path("workflowNodeId").asText().equals("join")).hasSize(1);
        assertThat(findNode(trueNodes, "after-join").path("status").asText()).isEqualTo("SUCCEEDED");

        UUID falseExecutionId = startExecution(workflowId, "{\"triggerInput\":{\"age\":15}}");
        awaitStatus(falseExecutionId, ExecutionStatus.SUCCEEDED);
        JsonNode falseNodes = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", falseExecutionId))
                .andReturn().getResponse().getContentAsString());
        assertThat(findNode(falseNodes, "true-a").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(falseNodes, "true-b").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(falseNodes, "false-path").path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode falseJoinInput = findNode(falseNodes, "join").path("inputData").path("inputs");
        assertThat(falseJoinInput.size()).isEqualTo(2);
        assertThat(falseJoinInput.has("false-path")).isTrue();
        assertThat(falseJoinInput.has("common")).isTrue();
        assertThat(findNode(falseNodes, "after-join").path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode logs = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/logs", trueExecutionId))
                .andReturn().getResponse().getContentAsString());
        JsonNode decisionLog = null;
        for (JsonNode log : logs) {
            if (log.path("details").path("selectedBranch").isTextual()) {
                decisionLog = log;
            }
        }
        assertThat(decisionLog).isNotNull();
        assertThat(decisionLog.path("details").path("selectedBranch").asText()).isEqualTo("TRUE");
        assertThat(decisionLog.path("details").has("actualValue")).isFalse();
    }

    @Test
    void executesNestedConditionalsAndSkipsEntireUnselectedSubgraph() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"trigger","type":"manual-trigger"},
                    {"id":"outer","type":"if-condition","configuration":{"field":"enabled","operator":"equals","value":true}},
                    {"id":"inner","type":"if-condition","configuration":{"field":"age","operator":"greater than or equal","value":18}},
                    {"id":"outer-false","type":"log-message","configuration":{"message":"Outer false"}},
                    {"id":"inner-true","type":"log-message","configuration":{"message":"Inner true"}},
                    {"id":"inner-false","type":"log-message","configuration":{"message":"Inner false"}},
                    {"id":"join","type":"log-message","configuration":{"message":"Joined"}},
                    {"id":"inactive-join","type":"log-message","configuration":{"message":"Must be skipped when inner branch is inactive"}}
                  ],
                  "connections":[
                    {"id":"outer-start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"outer","targetHandle":"input"},
                    {"id":"outer-true","sourceNodeId":"outer","sourceHandle":"true","targetNodeId":"inner","targetHandle":"input"},
                    {"id":"outer-false-edge","sourceNodeId":"outer","sourceHandle":"false","targetNodeId":"outer-false","targetHandle":"input"},
                    {"id":"inner-true-edge","sourceNodeId":"inner","sourceHandle":"true","targetNodeId":"inner-true","targetHandle":"input"},
                    {"id":"inner-false-edge","sourceNodeId":"inner","sourceHandle":"false","targetNodeId":"inner-false","targetHandle":"input"},
                    {"id":"join-outer","sourceNodeId":"outer-false","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"join-inner-true","sourceNodeId":"inner-true","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"join-inner-false","sourceNodeId":"inner-false","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                    {"id":"dead-join-true","sourceNodeId":"inner-true","sourceHandle":"output","targetNodeId":"inactive-join","targetHandle":"input"},
                    {"id":"dead-join-false","sourceNodeId":"inner-false","sourceHandle":"output","targetNodeId":"inactive-join","targetHandle":"input"}
                  ]
                }
                """);

        UUID outerFalseExecution = startExecution(workflowId, "{\"triggerInput\":{\"enabled\":false,\"age\":22}}");
        awaitStatus(outerFalseExecution, ExecutionStatus.SUCCEEDED);
        JsonNode outerFalseNodes = objectMapper.readTree(mockMvc.perform(
                get("/api/executions/{id}/nodes", outerFalseExecution)).andReturn().getResponse().getContentAsString());
        assertThat(findNode(outerFalseNodes, "inner").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(outerFalseNodes, "inner-true").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(outerFalseNodes, "inner-false").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(outerFalseNodes, "join").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(outerFalseNodes, "inactive-join").path("status").asText()).isEqualTo("SKIPPED");

        UUID innerFalseExecution = startExecution(workflowId, "{\"triggerInput\":{\"enabled\":true,\"age\":15}}");
        awaitStatus(innerFalseExecution, ExecutionStatus.SUCCEEDED);
        JsonNode innerFalseNodes = objectMapper.readTree(mockMvc.perform(
                get("/api/executions/{id}/nodes", innerFalseExecution)).andReturn().getResponse().getContentAsString());
        assertThat(findNode(innerFalseNodes, "inner-true").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(innerFalseNodes, "inner-false").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(innerFalseNodes, "outer-false").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(innerFalseNodes, "join").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(innerFalseNodes, "inactive-join").path("status").asText()).isEqualTo("SUCCEEDED");

        UUID innerTrueExecution = startExecution(workflowId, "{\"triggerInput\":{\"enabled\":true,\"age\":22}}");
        awaitStatus(innerTrueExecution, ExecutionStatus.SUCCEEDED);
        JsonNode innerTrueNodes = objectMapper.readTree(mockMvc.perform(
                get("/api/executions/{id}/nodes", innerTrueExecution)).andReturn().getResponse().getContentAsString());
        assertThat(findNode(innerTrueNodes, "inner-true").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(innerTrueNodes, "inner-false").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(innerTrueNodes, "outer-false").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(innerTrueNodes, "inactive-join").path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void conditionEvaluationErrorsFailTheConditionAndSkipBothBranches() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"trigger","type":"manual-trigger"},
                    {"id":"condition","type":"if-condition","configuration":{"field":"age","operator":"greater than","value":18}},
                    {"id":"yes","type":"log-message","configuration":{"message":"yes"}},
                    {"id":"no","type":"log-message","configuration":{"message":"no"}}
                  ],
                  "connections":[
                    {"id":"start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"condition","targetHandle":"input"},
                    {"id":"yes-edge","sourceNodeId":"condition","sourceHandle":"true","targetNodeId":"yes","targetHandle":"input"},
                    {"id":"no-edge","sourceNodeId":"condition","sourceHandle":"false","targetNodeId":"no","targetHandle":"input"}
                  ]
                }
                """);
        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{}}");
        awaitStatus(executionId, ExecutionStatus.FAILED);

        JsonNode nodes = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andReturn().getResponse().getContentAsString());
        assertThat(findNode(nodes, "condition").path("status").asText()).isEqualTo("FAILED");
        assertThat(findNode(nodes, "condition").path("errorMessage").asText()).contains("is missing");
        assertThat(findNode(nodes, "yes").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(findNode(nodes, "no").path("status").asText()).isEqualTo("SKIPPED");
    }

    @Test
    void routesLegacyConditionalHandlesWithoutMutatingTheSavedSnapshot() throws Exception {
        UUID workflowId = createWorkflow("""
                {
                  "nodes":[
                    {"id":"trigger","type":"manual-trigger"},
                    {"id":"condition","type":"if-condition","configuration":{"field":"age","operator":"greater than","value":18}},
                    {"id":"yes","type":"log-message","configuration":{"message":"yes"}},
                    {"id":"no","type":"log-message","configuration":{"message":"no"}}
                  ],
                  "connections":[
                    {"id":"start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"condition","targetHandle":"input"},
                    {"id":"legacy-yes","sourceNodeId":"condition","sourceHandle":"output","targetNodeId":"yes","targetHandle":"input"},
                    {"id":"legacy-no","sourceNodeId":"condition","sourceHandle":"output-2","targetNodeId":"no","targetHandle":"input"}
                  ]
                }
                """);
        UUID executionId = startExecution(workflowId, "{\"triggerInput\":{\"age\":20}}");
        awaitStatus(executionId, ExecutionStatus.SUCCEEDED);

        JsonNode snapshot = executionRepository.findById(executionId).orElseThrow().getWorkflowDefinition();
        assertThat(snapshot.path("connections").get(1).path("sourceHandle").asText()).isEqualTo("output");
        assertThat(snapshot.path("connections").get(2).path("sourceHandle").asText()).isEqualTo("output-2");
        JsonNode nodes = objectMapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andReturn().getResponse().getContentAsString());
        assertThat(findNode(nodes, "yes").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(findNode(nodes, "no").path("status").asText()).isEqualTo("SKIPPED");
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
