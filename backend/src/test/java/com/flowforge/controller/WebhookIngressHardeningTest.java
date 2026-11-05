package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.dto.workflow.CreateWorkflowRequest;
import com.flowforge.dto.workflow.UpdateWorkflowRequest;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.service.WebhookRegistrationService;
import com.flowforge.service.WorkflowService;
import com.flowforge.validation.WorkflowGraphValidator;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(WebhookIngressHardeningTest.BodyReadHook.class)
class WebhookIngressHardeningTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WorkflowService workflows;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WebhookRegistrationService registrations;
    @Autowired private WebhookRegistrationRepository registrationRepository;
    @Autowired private ExecutionRepository executions;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private WorkflowGraphValidator graphValidator;
    @Value("${flowforge.security.admin-token}") private String adminToken;

    @ParameterizedTest
    @ValueSource(strings = {"rotate", "disable", "node-inactive", "replace-node", "remove-node", "delete", "method", "authentication"})
    void mutationAfterPreliminaryAuthenticationCannotUseStaleAuthorization(String mutation) throws Exception {
        Fixture fixture = create("POST", "secret-token");
        long before = executions.count();
        Runnable mutate = () -> {
            switch (mutation) {
                case "rotate" -> registrations.regenerateSecret(fixture.workflowId, "hook");
                case "disable" -> workflows.updateWorkflow(fixture.workflowId, new UpdateWorkflowRequest(null, null, false, null));
                case "delete" -> workflows.deleteWorkflow(fixture.workflowId);
                default -> {
                    ObjectNode definition = definition("POST", "secret-token");
                    ObjectNode node = (ObjectNode) definition.path("nodes").get(0);
                    ObjectNode config = (ObjectNode) node.path("configuration");
                    switch (mutation) {
                        case "node-inactive" -> config.put("active", false);
                        case "method" -> config.put("method", "PUT");
                        case "authentication" -> {
                            config.put("authentication", "none");
                            config.put("method", "PUT");
                        }
                        case "replace-node" -> {
                            node.put("id", "replacement");
                            ((ObjectNode) definition.path("connections").get(0)).put("sourceNodeId", "replacement");
                        }
                        case "remove-node" -> definition.putArray("nodes");
                        default -> throw new AssertionError("Unknown mutation fixture");
                    }
                    workflows.updateWorkflow(fixture.workflowId, new UpdateWorkflowRequest(null, null, null, definition));
                }
            }
        };
        int expected = mutation.equals("rotate") ? 401 :
                (mutation.equals("method") || mutation.equals("authentication")) ? 405 : 404;
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId)
                        .header("X-FlowForge-Webhook-Secret", fixture.secret).contentType("application/json").content("{}")
                        .requestAttr("stage3-before-body-read", mutate))
                .andExpect(status().is(expected));
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void switchingPublicRegistrationToSecretDuringUploadRequiresNewSecret() throws Exception {
        Fixture fixture = create("POST", "none");
        long before = executions.count();
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("application/json").content("{}")
                        .requestAttr("stage3-before-body-read", (Runnable) () -> {
                            workflows.updateWorkflow(fixture.workflowId, new UpdateWorkflowRequest(null, null, null,
                                    definition("POST", "secret-token")));
                            registrations.regenerateSecret(fixture.workflowId, "hook");
                        }))
                .andExpect(status().isUnauthorized());
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void currentDefinitionAndRegistrationMismatchFailsClosed() throws Exception {
        Fixture fixture = create("POST", "none");
        long before = executions.count();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var workflow = workflowRepository.findByIdForRegistrationUpdate(fixture.workflowId).orElseThrow();
            ObjectNode definition = definition("PUT", "none");
            workflow.setDefinition(definition); // Deliberately inconsistent isolated test fixture; bypass normal synchronization.
            workflowRepository.saveAndFlush(workflow);
        });
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertThat(executions.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{} {}", "{\"duplicate\":1,\"duplicate\":2}", "   "})
    void malformedJsonDoesNotCreateExecutions(String payload) throws Exception {
        Fixture fixture = create("POST", "none");
        long before = executions.count();
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("application/json").content(payload))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        assertThat(executions.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "application/*", "*/*", "text/json", "text/plain;charset=ISO-8859-1"})
    void unsupportedContentTypesAreRejected(String contentType) throws Exception {
        Fixture fixture = create("POST", "none");
        long before = executions.count();
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType(contentType).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void missingInvalidDuplicateSecretsAndMissingRegistrationCannotExecute() throws Exception {
        Fixture fixture = create("POST", "secret-token");
        long before = executions.count();
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).header("X-FlowForge-Webhook-Secret", "invalid-test-value")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId)
                        .header("X-FlowForge-Webhook-Secret", fixture.secret, fixture.secret)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/{id}", "z".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(post("/api/webhooks/not-an-id")).andExpect(status().isNotFound());
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void methodMismatchReturnsAllowWithoutCreatingAnExecution() throws Exception {
        Fixture fixture = create("PUT", "none");
        long before = executions.count();
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "PUT"));
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void depthStringUtf8AndQueryLimitsRejectBeforeExecution() throws Exception {
        Fixture fixture = create("POST", "none");
        long before = executions.count();
        for (String payload : new String[] {"[".repeat(51)+"0"+"]".repeat(51), "\""+"x".repeat(262_145)+"\""}) {
            mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).contentType("text/plain").content(new byte[] {(byte)0xC3, 0x28}))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).queryParam("q", "x".repeat(8_193)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId).param("q", "x".repeat(2_049)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void rateLimitedRequestsDoNotCreateExecutions() throws Exception {
        Fixture fixture = create("POST", "secret-token");
        long before = executions.count();
        for (int i = 0; i < 60; i++) {
            mvc.perform(post("/api/webhooks/{id}", fixture.publicId).header("X-FlowForge-Webhook-Secret", "invalid-test-value"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/webhooks/{id}", fixture.publicId)).andExpect(status().isTooManyRequests());
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void acceptedDeliveriesCreateOneExecutionEachAndKeepSanitizedInput() throws Exception {
        Fixture fixture = create("POST", "secret-token");
        long before = executions.count();
        for (int i=0; i<2; i++) {
            String response = mvc.perform(post("/api/webhooks/{id}", fixture.publicId)
                            .header("X-FlowForge-Webhook-Secret", fixture.secret)
                            .header("Authorization", "Bearer excluded-test-value").header("Cookie", "session=excluded-test-value")
                            .header("User-Agent", "stage3-test").header("X-Request-Id", "request-test-id")
                            .contentType("application/problem+json").content("{\"order\":7}").param("source", "shop"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(true))
                    .andExpect(jsonPath("$.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
            UUID executionId = UUID.fromString(mapper.readTree(response).path("executionId").asText());
            awaitExecution(executionId);
            var execution = executions.findById(executionId).orElseThrow();
            assertThat(execution.getTriggerInput().path("body").path("order").asInt()).isEqualTo(7);
            assertThat(execution.getTriggerInput().path("query").path("source").get(0).asText()).isEqualTo("shop");
            assertThat(execution.getTriggerInput().path("headers").size()).isEqualTo(3);
            assertThat(execution.getTriggerInput().toString().contains(fixture.secret)).isFalse();
            assertThat(execution.getTriggerInput().toString().contains("excluded-test-value")).isFalse();
            assertThat(execution.getTriggerInput().path("receivedAt").asText()).isNotBlank();
        }
        assertThat(executions.count()).isEqualTo(before + 2);
    }

    @Test
    void authorizationAndSnapshotHoldParentLockUntilExecutionCommit() throws Exception {
        Fixture fixture = create("POST", "none");
        JsonNode beforeDefinition = workflowRepository.findById(fixture.workflowId).orElseThrow().getDefinition();
        long before = executions.count();
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            authorized.countDown();
            await(releaseExecution);
            return invocation.callRealMethod();
        }).when(graphValidator).validate(any(JsonNode.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ingress = executor.submit(() -> mvc.perform(post("/api/webhooks/{id}", fixture.publicId)
                    .contentType("application/json").content("{\"original\":true}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
            try {
                assertThat(authorized.await(5, TimeUnit.SECONDS)).isTrue();
                var mutation = executor.submit(() -> {
                    mutationStarted.countDown();
                    workflows.updateWorkflow(fixture.workflowId, new UpdateWorkflowRequest(null, null, false, null));
                });
                assertThat(mutationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> mutation.get(200, TimeUnit.MILLISECONDS));
                releaseExecution.countDown();
                String response = ingress.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                UUID executionId = UUID.fromString(mapper.readTree(response).path("executionId").asText());
                awaitExecution(executionId);
                var execution = executions.findById(executionId).orElseThrow();
                assertThat(execution.getWorkflowDefinition()).isEqualTo(beforeDefinition);
                assertThat(execution.getTriggerInput().path("body").path("original").asBoolean()).isTrue();
                assertThat(executions.count()).isEqualTo(before + 1);
                assertThat(workflowRepository.findById(fixture.workflowId).orElseThrow().isActive()).isFalse();
            } finally {
                releaseExecution.countDown();
            }
        }
    }

    private void awaitExecution(UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var state = executions.findById(id).orElseThrow().getStatus();
            if (state == com.flowforge.model.execution.ExecutionStatus.SUCCEEDED) return;
            if (state == com.flowforge.model.execution.ExecutionStatus.FAILED) break;
            Thread.sleep(10);
        }
        assertThat(executions.findById(id).orElseThrow().getStatus())
                .isEqualTo(com.flowforge.model.execution.ExecutionStatus.SUCCEEDED);
    }

    private Fixture create(String method, String authentication) {
        UUID id = workflows.createWorkflow(new CreateWorkflowRequest("Stage 3 fixture", "", true, definition(method, authentication))).id();
        String secret = authentication.equals("secret-token") ? registrations.regenerateSecret(id, "hook").secret() : null;
        return new Fixture(id, registrationRepository.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow().getWebhookId(), secret);
    }

    private ObjectNode definition(String method, String authentication) {
        ObjectNode definition = mapper.createObjectNode();
        var nodes = definition.putArray("nodes");
        nodes.addObject().put("id", "hook").put("type", "webhook-trigger").putObject("configuration")
                .put("method", method).put("authentication", authentication).put("active", true);
        nodes.addObject().put("id", "log").put("type", "log-message").putObject("configuration").put("message", "accepted");
        definition.putArray("connections").addObject().put("id", "edge").put("sourceNodeId", "hook")
                .put("sourceHandle", "output").put("targetNodeId", "log").put("targetHandle", "input");
        return definition;
    }

    private record Fixture(UUID workflowId, String publicId, String secret) {}

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test synchronization timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test synchronization interrupted");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BodyReadHook {
        @Bean
        Filter beforeBodyRead() {
            return (request, response, chain) -> {
                AtomicBoolean called = new AtomicBoolean();
                chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
                    @Override
                    public ServletInputStream getInputStream() throws IOException {
                        if (called.compareAndSet(false, true) && getAttribute("stage3-before-body-read") instanceof Runnable hook) hook.run();
                        return super.getInputStream();
                    }
                }, response);
            };
        }
    }
}
