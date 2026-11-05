package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.ai.AiProvider;
import com.flowforge.ai.AiProviderRegistry;
import com.flowforge.ai.AiResponse;
import com.flowforge.ai.AiUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MockMvcAdminAuthConfiguration.class)
class AiModelIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private AiProviderRegistry providers;

    @Test
    void aiOutputFlowsIntoDownstreamNodeWithoutRealProviderCall() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        when(providers.supports("openai")).thenReturn(true);
        when(providers.require("openai")).thenReturn(provider);
        when(provider.generate(any())).thenReturn(new AiResponse("openai", "gpt-4.1-mini", "Generated test answer", new AiUsage(4, 3, 7)));
        UUID workflowId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody("")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText());
        UUID executionId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerInput\":{\"topic\":\"quarterly results\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText());

        JsonNode run = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            run = mapper.readTree(mockMvc.perform(get("/api/executions/{id}", executionId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if ("SUCCEEDED".equals(run.path("status").asText()) || "FAILED".equals(run.path("status").asText())) break;
            Thread.sleep(20);
        }
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode nodes = mapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode ai = findNode(nodes, "ai");
        JsonNode downstream = findNode(nodes, "downstream");
        assertThat(ai.path("outputData").path("text").asText()).isEqualTo("Generated test answer");
        assertThat(downstream.path("inputData").path("text").asText()).isEqualTo("Generated test answer");
        assertThat(downstream.path("outputData").path("processed").asBoolean()).isTrue();
        String logs = mockMvc.perform(get("/api/executions/{id}/logs", executionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(logs).contains("AI Model request completed.").doesNotContain("quarterly results");
    }

    @Test
    void manualTriggerCanStartAiModelWorkflowWithLogOutput() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        when(providers.supports("openai")).thenReturn(true);
        when(providers.require("openai")).thenReturn(provider);
        when(provider.generate(any())).thenReturn(new AiResponse("openai", "gpt-4.1-mini", "Generated log answer", new AiUsage(5, 4, 9)));
        UUID workflowId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Manual AI log fixture","active":true,"definition":{"nodes":[
                                  {"id":"ai","type":"ai-model","configuration":{"provider":"openai","model":"gpt-4.1-mini","userPrompt":"Summarize {{input.topic}}","temperature":0.4,"maxOutputTokens":64}},
                                  {"id":"logger","type":"log-message","configuration":{"message":"AI completed"}},
                                  {"id":"trigger","type":"manual-trigger","configuration":{}}
                                ],"connections":[
                                  {"id":"a","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"ai","targetHandle":"input"},
                                  {"id":"b","sourceNodeId":"ai","sourceHandle":"output","targetNodeId":"logger","targetHandle":"input"}
                                ]}}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText());
        UUID executionId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerInput\":{\"topic\":\"support tickets\"}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText());

        JsonNode run = awaitTerminalExecution(executionId);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode nodes = mapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(findNode(nodes, "trigger").path("nodeType").asText()).isEqualTo("manual-trigger");
        assertThat(findNode(nodes, "ai").path("nodeType").asText()).isEqualTo("ai-model");
        assertThat(findNode(nodes, "logger").path("inputData").path("text").asText()).isEqualTo("Generated log answer");
        String logs = mockMvc.perform(get("/api/executions/{id}/logs", executionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(logs).contains("AI completed").doesNotContain("support tickets");
    }

    @Test
    void uiGeneratedManualAiLogWorkflowSavesReloadsAndExecutesWithNormalExecuteRequest() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        when(providers.supports("openai")).thenReturn(true);
        when(providers.require("openai")).thenReturn(provider);
        when(provider.generate(any())).thenReturn(new AiResponse("openai", "gpt-4.1-mini", "UI generated answer", new AiUsage(6, 4, 10)));

        UUID workflowId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uiGeneratedWorkflowBody("Manual AI UI fixture", """
                                [
                                  {"id":"node-1","type":"manual-trigger","name":"Manual Trigger","description":"Starts when someone triggers this automation.","position":{"x":120,"y":120},"configuration":{"triggerInfo":"Manual","description":"Run this workflow manually from the dashboard."}},
                                  {"id":"node-2","type":"ai-model","name":"AI Model","description":"Generates text from workflow data using a backend-configured provider.","position":{"x":380,"y":120},"configuration":{"provider":"openai","model":"gpt-4.1-mini","systemPrompt":"","userPrompt":"Summarize: {{input.topic}}","temperature":0.7,"maxOutputTokens":512}},
                                  {"id":"node-3","type":"log-message","name":"Log Message","description":"Writes messages to the execution log.","position":{"x":640,"y":120},"configuration":{"message":"AI completed"}}
                                ]
                                """, """
                                [
                                  {"id":"conn-1","sourceNodeId":"node-1","sourceHandle":"output","targetNodeId":"node-2","targetHandle":"input"},
                                  {"id":"conn-2","sourceNodeId":"node-2","sourceHandle":"output","targetNodeId":"node-3","targetHandle":"input"}
                                ]
                                """)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText());

        JsonNode saved = mapper.readTree(mockMvc.perform(get("/api/workflows/{id}", workflowId))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("definition");
        assertThat(saved.path("nodes").get(0).path("type").asText()).isEqualTo("manual-trigger");
        assertThat(saved.path("nodes").get(1).path("type").asText()).isEqualTo("ai-model");
        assertThat(saved.path("nodes").get(1).path("configuration").has("apiKey")).isFalse();

        UUID executionId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerInput\":{\"topic\":\"incident review\"},\"timeoutSeconds\":300}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText());

        assertThat(awaitTerminalExecution(executionId).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode nodes = mapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(findNode(nodes, "node-1").path("nodeType").asText()).isEqualTo("manual-trigger");
        assertThat(findNode(nodes, "node-2").path("nodeType").asText()).isEqualTo("ai-model");
        assertThat(findNode(nodes, "node-3").path("inputData").path("text").asText()).isEqualTo("UI generated answer");
    }

    @Test
    void manualSetDataAiLogWorkflowExecutesWithAiAsDownstreamProcessingNode() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        when(providers.supports("openai")).thenReturn(true);
        when(providers.require("openai")).thenReturn(provider);
        when(provider.generate(any())).thenReturn(new AiResponse("openai", "gpt-4.1-mini", "Set data answer", null));
        UUID workflowId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uiGeneratedWorkflowBody("Manual Set AI fixture", """
                                [
                                  {"id":"node-1","type":"manual-trigger","name":"Manual Trigger","description":"","position":{"x":120,"y":120},"configuration":{"triggerInfo":"Manual"}},
                                  {"id":"node-2","type":"set-data","name":"Set Data","description":"","position":{"x":360,"y":120},"configuration":{"key":"topic","value":"handoff notes"}},
                                  {"id":"node-3","type":"ai-model","name":"AI Model","description":"","position":{"x":600,"y":120},"configuration":{"provider":"openai","model":"gpt-4.1-mini","systemPrompt":"","userPrompt":"Summarize {{input.topic}}","temperature":0.2,"maxOutputTokens":128}},
                                  {"id":"node-4","type":"log-message","name":"Log Output","description":"","position":{"x":840,"y":120},"configuration":{"message":"AI logged"}}
                                ]
                                """, """
                                [
                                  {"id":"conn-1","sourceNodeId":"node-1","sourceHandle":"output","targetNodeId":"node-2","targetHandle":"input"},
                                  {"id":"conn-2","sourceNodeId":"node-2","sourceHandle":"output","targetNodeId":"node-3","targetHandle":"input"},
                                  {"id":"conn-3","sourceNodeId":"node-3","sourceHandle":"output","targetNodeId":"node-4","targetHandle":"input"}
                                ]
                                """)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText());
        UUID executionId = UUID.fromString(mapper.readTree(mockMvc.perform(post("/api/workflows/{id}/executions", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerInput\":{}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText());

        assertThat(awaitTerminalExecution(executionId).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode nodes = mapper.readTree(mockMvc.perform(get("/api/executions/{id}/nodes", executionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(findNode(nodes, "node-1").path("nodeType").asText()).isEqualTo("manual-trigger");
        assertThat(findNode(nodes, "node-3").path("inputData").path("topic").asText()).isEqualTo("handoff notes");
        assertThat(findNode(nodes, "node-4").path("inputData").path("text").asText()).isEqualTo("Set data answer");
    }

    @Test
    void rejectsCredentialFieldBeforeWorkflowPersistenceWithoutEchoingValue() throws Exception {
        String testOnlyValue = "test-only-value-that-must-not-be-persisted";
        String body = workflowBody(",\"apiKey\":\"" + testOnlyValue + "\"");
        String response = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(testOnlyValue);
    }

    private String workflowBody(String extraAiConfig) {
        return """
                {"name":"AI integration fixture","active":true,"definition":{"nodes":[
                  {"id":"trigger","type":"manual-trigger","configuration":{}},
                  {"id":"ai","type":"ai-model","configuration":{"provider":"openai","model":"gpt-4.1-mini","userPrompt":"Summarize {{input.topic}}","temperature":0.4,"maxOutputTokens":64%s}},
                  {"id":"downstream","type":"set-data","configuration":{"key":"processed","value":true}}
                ],"connections":[
                  {"id":"a","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"ai","targetHandle":"input"},
                  {"id":"b","sourceNodeId":"ai","sourceHandle":"output","targetNodeId":"downstream","targetHandle":"input"}
                ]}}
                """.formatted(extraAiConfig);
    }

    private String uiGeneratedWorkflowBody(String name, String nodes, String connections) {
        return """
                {"name":"%s","description":"UI-shaped fixture","active":true,"definition":{
                  "workflowId":"workflow-123",
                  "workflowName":"%s",
                  "nodes":%s,
                  "connections":%s,
                  "selectedNodeId":null,
                  "selectedConnectionId":null,
                  "isDirty":false
                }}
                """.formatted(name, name, nodes, connections);
    }

    private JsonNode awaitTerminalExecution(UUID executionId) throws Exception {
        JsonNode run = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            run = mapper.readTree(mockMvc.perform(get("/api/executions/{id}", executionId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if ("SUCCEEDED".equals(run.path("status").asText()) || "FAILED".equals(run.path("status").asText())) break;
            Thread.sleep(20);
        }
        return run;
    }

    private JsonNode findNode(JsonNode nodes, String id) {
        for (JsonNode node : nodes) if (id.equals(node.path("workflowNodeId").asText())) return node;
        throw new AssertionError("Missing node " + id);
    }
}
