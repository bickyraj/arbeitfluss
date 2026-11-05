package com.flowforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.entity.Workflow;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.repository.WorkflowRepository;
import com.flowforge.service.WebhookRegistrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookRegistrationIntegrationTest {
    private static final String ADMIN = "Bearer test-only-flowforge-admin-token-0123456789";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WorkflowRepository workflows;
    @Autowired private WebhookRegistrationRepository registrations;
    @MockitoSpyBean private WebhookRegistrationService registrationService;

    @Test
    void createsOpaqueRegistrationWithSafeTimestampsAndNoSecret() throws Exception {
        UUID id = create("secret-token", true, true);
        JsonNode registration = metadata(id);
        assertThat(registration.path("webhookId").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(registration.path("httpMethod").asText()).isEqualTo("POST");
        assertThat(registration.path("enabled").asBoolean()).isFalse();
        assertThat(registration.path("secretConfigured").asBoolean()).isFalse();
        assertThat(registration.path("createdAt").asText()).isNotBlank();
        assertThat(registration.path("updatedAt").asText()).isNotBlank();
        assertThat(registration.has("secret")).isFalse();
        assertThat(registration.has("secretHash")).isFalse();
        mvc.perform(get("/api/workflows/{id}/webhooks", id).header("Authorization", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].webhookId").value(registration.path("webhookId").asText()));
    }

    @Test
    void preservesIdAndHashAndUpdatesMethodOnSave() throws Exception {
        UUID id = create("secret-token", true, true);
        rotate(id);
        String publicId = metadata(id).path("webhookId").asText();
        String hash = storedHash(id);
        ObjectNode definition = definition("secret-token", true);
        ((ObjectNode) definition.at("/nodes/0/configuration")).put("method", "PUT");
        update(id, mapper.createObjectNode().set("definition", definition));
        assertThat(metadata(id).path("webhookId").asText()).isEqualTo(publicId);
        assertThat(metadata(id).path("httpMethod").asText()).isEqualTo("PUT");
        assertThat(hash.equals(storedHash(id))).isTrue();
    }

    @Test
    void generatesAndRotatesSecretReturningOnlySafeMetadataAlongsidePlaintext() throws Exception {
        UUID id = create("secret-token", true, true);
        JsonNode first = rotate(id);
        String secret = first.path("secret").asText();
        assertThat(secret.length()).isEqualTo(43);
        assertThat(digest(secret).equals(storedHash(id))).isTrue();
        assertThat(first.path("registration").path("enabled").asBoolean()).isTrue();
        assertThat(first.path("registration").path("secretConfigured").asBoolean()).isTrue();
        assertThat(first.path("registration").has("secretHash")).isFalse();
        String previousHash = storedHash(id);
        JsonNode second = rotate(id);
        assertThat(previousHash.equals(storedHash(id))).isFalse();
        assertThat(digest(second.path("secret").asText()).equals(storedHash(id))).isTrue();
        assertThat(metadata(id).toString().contains(secret)).isFalse();
        assertThat(mapper.writeValueAsString(workflows.findById(id).orElseThrow().getDefinition()).contains(secret)).isFalse();
    }

    @Test
    void activationOnlyUpdatePreservesDefinitionAndStableRegistration() throws Exception {
        UUID id = create("secret-token", true, true);
        rotate(id);
        JsonNode before = workflows.findById(id).orElseThrow().getDefinition();
        String publicId = metadata(id).path("webhookId").asText();
        update(id, mapper.createObjectNode().put("active", false));
        assertThat(metadata(id).path("enabled").asBoolean()).isFalse();
        assertThat(workflows.findById(id).orElseThrow().getDefinition()).isEqualTo(before);
        update(id, mapper.createObjectNode().put("active", true));
        assertThat(metadata(id).path("enabled").asBoolean()).isTrue();
        assertThat(metadata(id).path("webhookId").asText()).isEqualTo(publicId);
    }

    @Test
    void nodeInactiveAndWorkflowInactiveRemainDisabledEvenAfterRotation() throws Exception {
        UUID nodeInactive = create("secret-token", true, false);
        rotate(nodeInactive);
        assertThat(metadata(nodeInactive).path("enabled").asBoolean()).isFalse();
        UUID workflowInactive = create("secret-token", false, true);
        rotate(workflowInactive);
        assertThat(metadata(workflowInactive).path("enabled").asBoolean()).isFalse();
    }

    @Test
    void noneAuthenticationNeedsNoSecretAndSwitchingBackRequiresExplicitGeneration() throws Exception {
        UUID id = create("none", true, true);
        assertThat(metadata(id).path("enabled").asBoolean()).isTrue();
        mvc.perform(post("/api/workflows/{id}/webhooks/hook/secret", id).header("Authorization", ADMIN))
                .andExpect(status().isBadRequest());
        update(id, mapper.createObjectNode().set("definition", definition("secret-token", true)));
        assertThat(metadata(id).path("enabled").asBoolean()).isFalse();
        rotate(id);
        update(id, mapper.createObjectNode().set("definition", definition("none", true)));
        assertThat(storedHash(id) == null).isTrue();
        update(id, mapper.createObjectNode().set("definition", definition("secret-token", true)));
        assertThat(metadata(id).path("enabled").asBoolean()).isFalse();
    }

    @Test
    void stripsClientSecretAndRegistrationFieldsBeforePersistence() throws Exception {
        ObjectNode definition = definition("secret-token", true);
        ObjectNode config = (ObjectNode) definition.at("/nodes/0/configuration");
        for (String field : new String[] {"secret", "secretToken", "secretHash", "secret_hash", "webhookId", "url"}) {
            config.put(field, "untrusted-client-value");
        }
        UUID id = createWithDefinition(definition, true);
        JsonNode saved = workflows.findById(id).orElseThrow().getDefinition();
        assertThat(saved.toString().contains("untrusted-client-value")).isFalse();
        assertThat(storedHash(id) == null).isTrue();
        assertThat(metadata(id).path("enabled").asBoolean()).isFalse();
    }

    @Test
    void removingNodeAndDeletingWorkflowRemoveRegistrations() throws Exception {
        UUID id = create("none", true, true);
        ObjectNode empty = mapper.createObjectNode();
        empty.putArray("nodes");
        update(id, mapper.createObjectNode().set("definition", empty));
        assertThat(registrations.findAllByWorkflow_Id(id)).isEmpty();
        mvc.perform(get("/api/workflows/{id}/webhooks/hook", id).header("Authorization", ADMIN))
                .andExpect(status().isNotFound());
        UUID deleted = create("none", true, true);
        mvc.perform(delete("/api/workflows/{id}", deleted).header("Authorization", ADMIN))
                .andExpect(status().isNoContent());
        assertThat(registrations.findAllByWorkflow_Id(deleted)).isEmpty();
    }

    @Test
    void invalidConfigurationRollsBackWorkflowUpdate() throws Exception {
        UUID id = create("none", true, true);
        JsonNode before = workflows.findById(id).orElseThrow().getDefinition();
        ObjectNode invalid = definition("none", true);
        ((ObjectNode) invalid.at("/nodes/0/configuration")).put("method", "INVALID");
        mvc.perform(put("/api/workflows/{id}", id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("name", "must-roll-back").set("definition", invalid))))
                .andExpect(status().isBadRequest());
        assertThat(workflows.findById(id).orElseThrow().getName()).isEqualTo("Registration test");
        assertThat(workflows.findById(id).orElseThrow().getDefinition()).isEqualTo(before);
        assertThat(metadata(id).path("httpMethod").asText()).isEqualTo("POST");
    }

    @Test
    void reconciliationFailureRollsBackAlreadyFlushedWorkflowAndRegistrationChanges() throws Exception {
        long workflowCount = workflows.count();
        long registrationCount = registrations.count();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            registrations.flush();
            throw new IllegalStateException("Simulated reconciliation failure");
        }).when(registrationService).synchronizeWorkflow(any(Workflow.class));
        try {
            mvc.perform(post("/api/workflows").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("name", "Rollback test")
                                    .set("definition", definition("none", true)))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Unexpected server error"));
            assertThat(workflows.count()).isEqualTo(workflowCount);
            assertThat(registrations.count()).isEqualTo(registrationCount);
        } finally {
            reset(registrationService);
        }
    }

    @Test
    void reconciliationFailureRollsBackUpdateAndKeepsExistingRegistration() throws Exception {
        UUID id = create("secret-token", true, true);
        rotate(id);
        String publicId = metadata(id).path("webhookId").asText();
        String hash = storedHash(id);
        JsonNode before = workflows.findById(id).orElseThrow().getDefinition();
        ObjectNode changed = definition("none", false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            registrations.flush();
            throw new IllegalStateException("Simulated reconciliation failure");
        }).when(registrationService).synchronizeWorkflow(any(Workflow.class));
        try {
            mvc.perform(put("/api/workflows/{id}", id).header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("active", false)
                                    .set("definition", changed))))
                    .andExpect(status().isInternalServerError());
            assertThat(workflows.findById(id).orElseThrow().isActive()).isTrue();
            assertThat(workflows.findById(id).orElseThrow().getDefinition()).isEqualTo(before);
            assertThat(metadata(id).path("webhookId").asText()).isEqualTo(publicId);
            assertThat(metadata(id).path("enabled").asBoolean()).isTrue();
            assertThat(hash.equals(storedHash(id))).isTrue();
        } finally {
            reset(registrationService);
        }
    }

    @Test
    void rejectsDuplicateIdsAndInvalidConfigurationTypes() throws Exception {
        ObjectNode definition = definition("none", true);
        ((com.fasterxml.jackson.databind.node.ArrayNode) definition.path("nodes")).add(definition.path("nodes").get(0).deepCopy());
        mvc.perform(post("/api/workflows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("name", "Invalid").set("definition", definition))))
                .andExpect(status().isBadRequest());
        ObjectNode invalidType = definition("none", true);
        ((ObjectNode) invalidType.at("/nodes/0/configuration")).put("active", "false");
        mvc.perform(post("/api/workflows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("name", "Invalid").set("definition", invalidType))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managementRoutesRequireAdministratorAndUnknownRegistrationsReturn404() throws Exception {
        UUID id = create("none", true, true);
        mvc.perform(get("/api/workflows/{id}/webhooks", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workflows/{id}/webhooks/hook", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/workflows/{id}/webhooks/hook/secret", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workflows/{id}/webhooks/missing", id).header("Authorization", ADMIN)).andExpect(status().isNotFound());
        mvc.perform(get("/api/workflows/{id}/webhooks", UUID.randomUUID()).header("Authorization", ADMIN)).andExpect(status().isNotFound());
        mvc.perform(post("/api/workflows/{id}/webhooks/missing/secret", id).header("Authorization", ADMIN)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"node-1730812345678-a1b2", "node-1", "hook_ID-123"})
    void acceptsFrontendGeneratedAndPathSafeNodeIds(String nodeId) throws Exception {
        ObjectNode definition = definition("none", true);
        ((ObjectNode) definition.path("nodes").get(0)).put("id", nodeId);
        UUID id = createWithDefinition(definition, true);
        mvc.perform(get("/api/workflows/{id}/webhooks/{nodeId}", id, nodeId).header("Authorization", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodeId").value(nodeId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "node/segment", "node space", "node%2Fsegment", "node?query", "node#fragment", ".", "..", "node.segment", "node;parameter", "node\\segment"})
    void rejectsUnsafeNodeIdsBeforePersistingWorkflow(String nodeId) throws Exception {
        long workflowCount = workflows.count();
        long registrationCount = registrations.count();
        ObjectNode definition = definition("none", true);
        ((ObjectNode) definition.path("nodes").get(0)).put("id", nodeId);
        mvc.perform(post("/api/workflows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("name", "Invalid ID fixture")
                                .set("definition", definition))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Webhook Trigger node ID must contain 1 to 255 letters, digits, underscores or hyphens"));
        assertThat(workflows.count()).isEqualTo(workflowCount);
        assertThat(registrations.count()).isEqualTo(registrationCount);
    }

    @Test
    void accepts255CharacterNodeIdAndRejects256Characters() throws Exception {
        ObjectNode definition = definition("none", true);
        ((ObjectNode) definition.path("nodes").get(0)).put("id", "n".repeat(255));
        UUID id = createWithDefinition(definition, true);
        mvc.perform(get("/api/workflows/{id}/webhooks/{nodeId}", id, "n".repeat(255)).header("Authorization", ADMIN))
                .andExpect(status().isOk());
        ((ObjectNode) definition.path("nodes").get(0)).put("id", "n".repeat(256));
        mvc.perform(put("/api/workflows/{id}", id).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(mapper.createObjectNode().set("definition", definition))))
                .andExpect(status().isBadRequest());
        assertThat(registrations.findAllByWorkflow_Id(id).getFirst().getNodeId().length()).isEqualTo(255);
        assertThat(workflows.findById(id).orElseThrow().getDefinition().path("nodes").get(0).path("id").asText().length())
                .isEqualTo(255);
    }

    private ObjectNode definition(String authentication, boolean nodeActive) {
        ObjectNode definition = mapper.createObjectNode();
        ObjectNode node = definition.putArray("nodes").addObject().put("id", "hook").put("type", "webhook-trigger");
        node.putObject("configuration").put("method", "POST").put("authentication", authentication).put("active", nodeActive);
        definition.putArray("connections");
        return definition;
    }

    private UUID create(String authentication, boolean workflowActive, boolean nodeActive) throws Exception {
        return createWithDefinition(definition(authentication, nodeActive), workflowActive);
    }

    private UUID createWithDefinition(ObjectNode definition, boolean active) throws Exception {
        String response = mvc.perform(post("/api/workflows").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("name", "Registration test")
                                .put("active", active).set("definition", definition))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("id").asText());
    }

    private void update(UUID id, JsonNode payload) throws Exception {
        mvc.perform(put("/api/workflows/{id}", id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk());
    }

    private JsonNode metadata(UUID id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/workflows/{id}/webhooks/hook", id).header("Authorization", ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode rotate(UUID id) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/workflows/{id}/webhooks/hook/secret", id).header("Authorization", ADMIN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }

    private String storedHash(UUID id) {
        return registrations.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow().getSecretHash();
    }

    private String digest(String secret) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    }
}
