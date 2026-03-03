package com.debateai.client;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProviderDetector {

    public String detectProviderFromKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API key is blank or missing");
        }

        String normalized = apiKey.trim();
        if (normalized.startsWith("sk-ant")) {
            return "anthropic";
        }
        if (normalized.startsWith("sk-")) {
            return "openai";
        }
        if (normalized.startsWith("AIza")) {
            return "gemini";
        }

        throw new IllegalArgumentException("Unknown API key format");
    }
}
