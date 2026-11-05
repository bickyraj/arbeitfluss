package com.flowforge.controller;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestConfiguration(proxyBeanMethods = false)
public class MockMvcAdminAuthConfiguration {
    @Bean
    MockMvcBuilderCustomizer authenticatedMockMvcRequests() {
        return builder -> builder.defaultRequest(get("/")
                .header("Authorization", "Bearer test-only-flowforge-admin-token-0123456789"));
    }
}
