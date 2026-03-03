package com.debateai.client;

public record LLMGenerationRequest(
        String model,
        double temperature,
        String systemPrompt,
        String userPrompt,
        long timeoutMillis,
        int maxAttempts
) {
    public LLMGenerationRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (temperature < 0.0d || temperature > 1.0d) {
            throw new IllegalArgumentException("temperature must be in [0,1]");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank");
        }
        if (timeoutMillis < 1000L) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1000");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
