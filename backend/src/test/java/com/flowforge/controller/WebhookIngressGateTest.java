package com.flowforge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.dto.workflow.CreateWorkflowRequest;
import com.flowforge.exception.WebhookRequestRejectedException;
import com.flowforge.repository.ExecutionRepository;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.service.ExecutionService;
import com.flowforge.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "flowforge.webhooks.ingress-enabled=false")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class WebhookIngressGateTest {
    @Autowired private MockMvc mvc;
    @Autowired private WorkflowService workflows;
    @Autowired private WebhookRegistrationRepository registrations;
    @Autowired private ExecutionRepository executions;
    @Autowired private ExecutionService executionService;
    @Autowired private ObjectMapper mapper;
    @Value("${flowforge.security.admin-token}") private String adminToken;

    @Test
    void disabledGateRejectsHttpAndDirectServiceButPreservesRegistrationManagement() throws Exception {
        var definition = mapper.createObjectNode();
        definition.putArray("nodes").addObject().put("id", "hook").put("type", "webhook-trigger")
                .putObject("configuration").put("authentication", "none");
        var id = workflows.createWorkflow(new CreateWorkflowRequest("Gate fixture", "", true, definition)).id();
        var registration = registrations.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow();
        long before = executions.count();
        mvc.perform(post("/api/webhooks/{id}", registration.getWebhookId()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        var rejected = assertThrows(WebhookRequestRejectedException.class, () -> executionService.startWebhookExecution(
                registration.getWebhookId(), "POST", null, mapper.createObjectNode()));
        assertThat(rejected.status().value()).isEqualTo(404);
        assertThat(executions.count()).isEqualTo(before);
        mvc.perform(get("/api/workflows/{id}/webhooks", id).header("Authorization", "Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void disabledIngressDoesNotDisableManualExecution() throws Exception {
        var definition = mapper.createObjectNode();
        definition.putArray("nodes").addObject().put("id", "manual").put("type", "manual-trigger");
        definition.putArray("connections");
        var id = workflows.createWorkflow(new CreateWorkflowRequest("Manual gate fixture", "", true, definition)).id();
        mvc.perform(post("/api/workflows/{id}/executions", id).header("Authorization", "Bearer "+adminToken)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
    }
}
