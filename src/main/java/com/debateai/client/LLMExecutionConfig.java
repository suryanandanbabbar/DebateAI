package com.debateai.client;

public record LLMExecutionConfig(
        String model,
        double temperature,
        long timeoutMillis,
        int maxAttempts
) {
    public LLMExecutionConfig {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (temperature < 0.0d || temperature > 1.0d) {
            throw new IllegalArgumentException("temperature must be in [0,1]");
        }
        if (timeoutMillis < 1000L) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1000");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
