package com.flowforge.controller;

import com.flowforge.exception.ApiExceptionHandler;
import com.flowforge.security.AdminApiAuthenticationFilter;
import com.flowforge.security.AdminTokenService;
import com.flowforge.service.WebhookRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@WebMvcTest(controllers = {WebhookManagementController.class, ApiAuthController.class})
@Import({AdminTokenService.class, AdminApiAuthenticationFilter.class})
@ActiveProfiles("test")
class WebhookManagementErrorTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private WebhookRegistrationService registrations;
    @Value("${flowforge.security.admin-token}") private String testToken;

    @Test
    void malformedWorkflowUuidReturns400WithoutEchoingValue() throws Exception {
        mvc.perform(get("/api/workflows/private-invalid-value/webhooks").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request parameter"))
                .andExpect(content().string(not(containsString("private-invalid-value"))));
    }

    @Test
    void unsupportedManagementMethodReturns405WithAllowHeader() throws Exception {
        mvc.perform(post("/api/workflows/{id}/webhooks", UUID.randomUUID()).header("Authorization", bearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.message").value("HTTP method not supported"));
    }

    @Test
    void missingAndIncorrectAuthenticationStillTakePrecedenceOverRouteErrors() throws Exception {
        mvc.perform(get("/api/workflows/invalid/webhooks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/workflows/{id}/webhooks", UUID.randomUUID())
                        .header("Authorization", "Bearer invalid-test-credential"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/verify")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/verify").header("Authorization", bearer())).andExpect(status().isNoContent());
    }

    @Test
    void missingServerConfigurationReturns503ForManagementAndVerification() throws Exception {
        AdminTokenService unconfigured = new AdminTokenService("");
        MockMvc withoutToken = standaloneSetup(new WebhookManagementController(registrations), new ApiAuthController(unconfigured))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new AdminApiAuthenticationFilter(unconfigured)).build();
        withoutToken.perform(get("/api/workflows/invalid/webhooks").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable());
        withoutToken.perform(post("/api/auth/verify").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unexpectedFailureRemainsGeneric500() throws Exception {
        UUID id = UUID.randomUUID();
        when(registrations.listWorkflowWebhooks(id)).thenThrow(new IllegalStateException("private-diagnostic-value"));
        mvc.perform(get("/api/workflows/{id}/webhooks", id).header("Authorization", bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected server error"))
                .andExpect(content().string(not(containsString("private-diagnostic-value"))));
    }

    private String bearer() {
        return "Bearer " + testToken;
    }
}
