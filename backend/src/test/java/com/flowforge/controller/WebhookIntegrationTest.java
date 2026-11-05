package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionStatus;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookIntegrationTest {
    private static final String WEBHOOK_SECRET_HEADER = "X-FlowForge-Webhook-Secret";
    private static final String ADMIN_TOKEN = "Bearer test-only-flowforge-admin-token-0123456789";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WebhookRegistrationRepository registrationRepository;

    @Test
    void createsStableRegistrationAndKeepsSecretOutOfWorkflowResponses() throws Exception {
        UUID workflowId = createWebhookWorkflow("POST", "secret-token", true);
        JsonNode registration = getRegistration(workflowId);
        String webhookId = registration.path("webhookId").asText();

        assertThat(webhookId).hasSizeGreaterThanOrEqualTo(40);
        assertThat(registration.path("enabled").asBoolean()).isFalse();
        assertThat(registration.path("secretConfigured").asBoolean()).isFalse();

        String secret = mockMvc.perform(post("/api/workflows/{id}/webhooks/webhook-node/secret", workflowId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String plaintextSecret = objectMapper.readTree(secret).path("secret").asText();
        assertThat(plaintextSecret).hasSize(43);
        assertThat(objectMapper.readTree(workflowDefinition("PUT", "secret-token", true, null))
                .at("/nodes/0/configuration/method").asText()).isEqualTo("PUT");

        mockMvc.perform(put("/api/workflows/{id}", workflowId)
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" +
                                workflowDefinition("PUT", "secret-token", true, "never-return-this") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.nodes[0].configuration.method").value("PUT"))
                .andExpect(jsonPath("$.definition.nodes[0].configuration.secret").doesNotExist());

        JsonNode updatedRegistration = getRegistration(workflowId);
        assertThat(updatedRegistration.path("webhookId").asText()).isEqualTo(webhookId);
        assertThat(updatedRegistration.path("httpMethod").asText()).isEqualTo("PUT");
        assertThat(updatedRegistration.path("enabled").asBoolean()).isTrue();
        assertThat(registrationRepository.findAllByWorkflow_Id(workflowId).getFirst().getSecretHash())
                .hasSize(64)
                .isNotEqualTo(plaintextSecret);

        mockMvc.perform(post("/api/workflows/{id}/webhooks/webhook-node/secret", workflowId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty());
        assertThat(getRegistration(workflowId).path("webhookId").asText()).isEqualTo(webhookId);

        mockMvc.perform(put("/api/webhooks/{id}", webhookId)
                        .header("Authorization", ADMIN_TOKEN)
                        .header(WEBHOOK_SECRET_HEADER, plaintextSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatesBeforeCreatingWebhookExecutionAndPersistsOnlySafeInput() throws Exception {
        UUID workflowId = createWebhookWorkflow("POST", "secret-token", true);
        String secretResponse = mockMvc.perform(post("/api/workflows/{id}/webhooks/webhook-node/secret", workflowId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(secretResponse).path("secret").asText();
        String webhookId = getRegistration(workflowId).path("webhookId").asText();
        long previousExecutions = executionRepository.count();

        mockMvc.perform(post("/api/webhooks/{id}", webhookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"order_123\",\"amount\":99.99}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/webhooks/{id}", webhookId)
                        .header(WEBHOOK_SECRET_HEADER, "incorrect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"order_123\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(executionRepository.count()).isEqualTo(previousExecutions);

        String response = mockMvc.perform(post("/api/webhooks/{id}", webhookId)
                        .header(WEBHOOK_SECRET_HEADER, secret)
                        .header("Authorization", "Bearer must-not-be-copied")
                        .header("Cookie", "session=private")
                        .header("User-Agent", "webhook-test")
                        .param("source", "shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"order_123\",\"amount\":99.99}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();

        UUID executionId = UUID.fromString(objectMapper.readTree(response).path("executionId").asText());
        awaitExecution(executionId);
        Execution execution = executionRepository.findById(executionId).orElseThrow();
        assertThat(execution.getTriggerType().name()).isEqualTo("WEBHOOK");
        assertThat(execution.getTriggerInput().path("body").path("orderId").asText()).isEqualTo("order_123");
        assertThat(execution.getTriggerInput().path("query").path("source").get(0).asText()).isEqualTo("shop");
        assertThat(execution.getTriggerInput().path("headers").path("user-agent").asText()).isEqualTo("webhook-test");
        assertThat(execution.getTriggerInput().toString()).doesNotContain(secret, "must-not-be-copied", "session=private");
        mockMvc.perform(get("/api/executions/{id}", executionId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerType").value("WEBHOOK"));
    }

    @Test
    void enforcesMethodPayloadSizeUnknownAndInactiveResponses() throws Exception {
        UUID workflowId = createWebhookWorkflow("POST", "secret-token", true);
        String secret = objectMapper.readTree(mockMvc.perform(post("/api/workflows/{id}/webhooks/webhook-node/secret", workflowId)
                        .header("Authorization", ADMIN_TOKEN))
                .andReturn().getResponse().getContentAsString()).path("secret").asText();
        String webhookId = getRegistration(workflowId).path("webhookId").asText();

        mockMvc.perform(put("/api/webhooks/{id}", webhookId)
                        .header("Authorization", ADMIN_TOKEN)
                        .header(WEBHOOK_SECRET_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mockMvc.perform(post("/api/webhooks/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/webhooks/{id}", webhookId)
                        .header(WEBHOOK_SECRET_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/webhooks/{id}", webhookId)
                        .header(WEBHOOK_SECRET_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(1_048_577)))
                .andExpect(status().isPayloadTooLarge());

        mockMvc.perform(put("/api/workflows/{id}", workflowId)
                        .header("Authorization", ADMIN_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false,\"definition\":" +
                                        objectMapper.writeValueAsString(objectMapper.readTree(
                                                workflowDefinition("POST", "secret-token", true, null))) + "}"))
                .andExpect(status().isOk());
        assertThat(getRegistration(workflowId).path("enabled").asBoolean()).isFalse();
        mockMvc.perform(post("/api/webhooks/{id}", webhookId)
                        .header(WEBHOOK_SECRET_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/workflows/{id}", workflowId)
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk());
        assertThat(getRegistration(workflowId).path("webhookId").asText()).isEqualTo(webhookId);
        assertThat(getRegistration(workflowId).path("enabled").asBoolean()).isTrue();
    }

    @Test
    void acceptsGetWebhookWhenConfiguredAndPreservesQueryInput() throws Exception {
        UUID workflowId = createWebhookWorkflow("GET", "none", true);
        String webhookId = getRegistration(workflowId).path("webhookId").asText();

        String response = mockMvc.perform(get("/api/webhooks/{id}", webhookId)
                        .header("Authorization", ADMIN_TOKEN)
                        .param("source", "healthcheck"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID executionId = UUID.fromString(objectMapper.readTree(response).path("executionId").asText());
        awaitExecution(executionId);
        Execution execution = executionRepository.findById(executionId).orElseThrow();
        assertThat(execution.getTriggerType().name()).isEqualTo("WEBHOOK");
        assertThat(execution.getTriggerInput().path("method").asText()).isEqualTo("GET");
        assertThat(execution.getTriggerInput().path("query").path("source").get(0).asText())
                .isEqualTo("healthcheck");
    }

    @Test
    void requiresAdministratorTokenForManagementApis() throws Exception {
        mockMvc.perform(get("/api/workflows").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"invalid\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletesRegistrationWhenWorkflowIsDeleted() throws Exception {
        UUID workflowId = createWebhookWorkflow("POST", "none", true);
        String webhookId = getRegistration(workflowId).path("webhookId").asText();
        mockMvc.perform(delete("/api/workflows/{id}", workflowId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isNoContent());
        assertThat(registrationRepository.findByWebhookId(webhookId)).isEmpty();
        assertThat(workflowRepository.findById(workflowId)).isEmpty();
    }

    private UUID createWebhookWorkflow(String method, String authentication, boolean nodeActive) throws Exception {
        String definition = workflowDefinition(method, authentication, nodeActive, null);
        String response = mockMvc.perform(post("/api/workflows")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Webhook test\",\"active\":true,\"definition\":" + definition + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private String workflowDefinition(String method, String authentication, boolean nodeActive, String secret) throws Exception {
        JsonNode definition = objectMapper.readTree("""
                {
                  "workflowId":"webhook-test",
                  "workflowName":"Webhook test",
                  "nodes":[
                    {"id":"webhook-node","type":"webhook-trigger","name":"Webhook","configuration":{
                      "method":"%s","authentication":"%s","active":%s%s
                    }},
                    {"id":"logger","type":"log-message","name":"Log","configuration":{"message":"accepted"}}
                  ],
                  "connections":[
                    {"id":"edge","sourceNodeId":"webhook-node","sourceHandle":"output","targetNodeId":"logger","targetHandle":"input"}
                  ]
                }
                """.formatted(method, authentication, nodeActive, secret == null ? "" : ",\"secret\":\"" + secret + "\""));
        return objectMapper.writeValueAsString(definition);
    }

    private JsonNode getRegistration(UUID workflowId) throws Exception {
        String result = mockMvc.perform(get("/api/workflows/{id}/webhooks", workflowId)
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(result).get(0);
    }

    private void awaitExecution(UUID executionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ExecutionStatus status = executionRepository.findById(executionId)
                    .orElseThrow()
                    .getStatus();
            if (status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.FAILED) {
                assertThat(status).isEqualTo(ExecutionStatus.SUCCEEDED);
                return;
            }
            Thread.sleep(15);
        }
        assertThat(executionRepository.findById(executionId).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.SUCCEEDED);
    }
}
