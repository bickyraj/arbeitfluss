package com.flowforge.ai;

public interface AiProvider {
    String id();
    AiResponse generate(AiRequest request);
}
