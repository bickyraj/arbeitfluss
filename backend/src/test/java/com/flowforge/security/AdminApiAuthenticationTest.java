package com.flowforge.security;

import com.flowforge.controller.ApiAuthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@SpringJUnitConfig(AdminTokenService.class)
@TestPropertySource(properties = "flowforge.security.admin-token=test-only-admin-token-for-authentication-0123456789")
class AdminApiAuthenticationTest {
    @Value("${flowforge.security.admin-token}") private String token;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mockMvc(new AdminTokenService(token));
    }

    private MockMvc mockMvc(AdminTokenService service) {
        return standaloneSetup(new ApiAuthController(service), new ProbeController())
                .addFilters(new AdminApiAuthenticationFilter(service)).build();
    }

    @Test
    void acceptsValidBearerAndDoesNotExposeToken() throws Exception {
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(post("/api/auth/verify").header("Authorization", "bearer " + token))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer incorrect", "Basic credentials", "Bearer", "Bearer ",
            "Bearer  token", "Bearer token extra", "Bearer token,other", "Bearer token\t"})
    void rejectsMissingIncorrectAndMalformedCredentials(String header) throws Exception {
        var management = get("/api/workflows");
        var verify = post("/api/auth/verify");
        if (header != null) {
            management.header("Authorization", header);
            verify.header("Authorization", header);
        }
        mvc.perform(management).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(verify).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateHeadersAndNonHeaderCredentials() throws Exception {
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + token, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workflows").param("token", token)
                        .cookie(new jakarta.servlet.http.Cookie("token", token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/verify").contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingConfigurationFailsClosedWithoutDisablingHealth(String configuredToken) throws Exception {
        var unconfigured = mockMvc(new AdminTokenService(configuredToken));
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get("/api/workflows"), post("/api/auth/verify")}) {
            String response = unconfigured.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Administrator API authentication is not configured"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(token, "Exception", "stackTrace");
        }
        unconfigured.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/workflows", "/api/executions", "/api/workflows/123/executions",
            "/api/webhooks", "/api/webhooks/id/secret", "/api/webhooks/id/", "/api/webhooks/id/management",
            "/api/webhooks/id%2Fsecret", "/api;parameter/workflows", "/%61pi/workflows", "/api/auth/verify/", "/api/auth/verify/extra"})
    void protectsManagementPathsIncludingSimilarPublicPaths(String path) throws Exception {
        mvc.perform(post(java.net.URI.create(path))).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyExactPostWebhookShapeIsPublicAndContextPathIsHandled() throws Exception {
        // No webhook controller is installed: reaching MVC's 404 proves the filter allowed the request.
        mvc.perform(post("/api/webhooks/opaque_Id-123")).andExpect(status().isNotFound());
        for (String method : new String[] {"GET", "PUT", "PATCH", "DELETE", "HEAD"}) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/webhooks/opaque_Id-123"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/verify")).andExpect(status().isUnauthorized());
        mvc.perform(get("/flowforge/api/workflows").contextPath("/flowforge"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preflightReachesCorsProcessingAndNonApiRoutesRemainPublic() throws Exception {
        var policy = new org.springframework.web.cors.CorsConfiguration();
        policy.setAllowedOrigins(java.util.List.of("https://client.example"));
        policy.setAllowedMethods(java.util.List.of("GET"));
        policy.setAllowedHeaders(java.util.List.of("authorization"));
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/cors", policy);
        var corsMvc = standaloneSetup(new ProbeController())
                .addFilters(new AdminApiAuthenticationFilter(new AdminTokenService(null)),
                        new org.springframework.web.filter.CorsFilter(source))
                .build();
        // The MVC CORS policy remains authoritative; the auth filter must not return 401/503.
        corsMvc.perform(options("/api/workflows").header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isForbidden());
        corsMvc.perform(options("/api/cors").header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://client.example"))
                .andExpect(header().string("Access-Control-Allow-Headers", "authorization"));
        mvc.perform(options("/api/workflows")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/api/workflows", "/actuator/health"})
        String probe() { return "ok"; }

        @org.springframework.web.bind.annotation.CrossOrigin(origins = "https://client.example")
        @GetMapping("/api/cors")
        String corsProbe() { return "ok"; }
    }
}
