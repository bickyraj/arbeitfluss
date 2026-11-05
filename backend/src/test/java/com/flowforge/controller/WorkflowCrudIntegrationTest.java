package com.flowforge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.dto.workflow.CreateWorkflowRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.hasItem;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Test
    void workflowCrudLifecycleWorks() throws Exception {
        String payload = objectMapper.writeValueAsString(new CreateWorkflowRequest(
                "Customer Onboarding",
                "Initial onboarding workflow",
                true,
                objectMapper.readTree("{\"workflowId\":\"wf-1\",\"workflowName\":\"Customer Onboarding\",\"nodes\":[],\"connections\":[],\"isDirty\":false}")
        ));

        String response = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Customer Onboarding"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Customer Onboarding")));

        mockMvc.perform(put("/api/workflows/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Workflow\",\"active\":false,\"definition\":{\"workflowId\":\"wf-updated\",\"workflowName\":\"Updated Workflow\",\"nodes\":[],\"connections\":[]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Workflow"))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/workflows/{id}", id))
                .andExpect(status().isNoContent());
    }
}
