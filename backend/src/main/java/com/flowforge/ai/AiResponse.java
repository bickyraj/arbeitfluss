package com.flowforge.ai;

public record AiResponse(String provider, String model, String text, AiUsage usage) {}
