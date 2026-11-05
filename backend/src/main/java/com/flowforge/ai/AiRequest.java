package com.flowforge.ai;

public record AiRequest(
        String model,
        String systemMessage,
        String userMessage,
        double temperature,
        int maxOutputTokens,
        int timeoutSeconds
) {}
