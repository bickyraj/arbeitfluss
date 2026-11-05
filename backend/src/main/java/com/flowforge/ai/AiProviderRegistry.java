package com.flowforge.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AiProviderRegistry {
    private final Map<String, AiProvider> providers;

    public AiProviderRegistry(List<AiProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                AiProvider::id, Function.identity()));
    }

    public AiProvider require(String id) {
        AiProvider provider = providers.get(id);
        if (provider == null) {
            throw new AiProviderException("AI_PROVIDER_UNSUPPORTED", "AI provider is not supported.");
        }
        return provider;
    }

    public boolean supports(String id) { return providers.containsKey(id); }
}
