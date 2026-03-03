package com.debateai.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LLMClientFactory {

    private final Map<String, LLMClient> clientsByProvider;

    public LLMClientFactory(List<LLMClient> clients) {
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toUnmodifiableMap(client -> normalize(client.provider()), Function.identity()));
    }

    public LLMClient getClient(String providerName) {
        String normalized = normalize(providerName);
        LLMClient client = clientsByProvider.get(normalized);
        if (client == null) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + providerName);
        }
        return client;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("provider name must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
