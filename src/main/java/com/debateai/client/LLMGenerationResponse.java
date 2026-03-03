package com.debateai.client;

public record LLMGenerationResponse(
        String content,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
    public LLMGenerationResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
