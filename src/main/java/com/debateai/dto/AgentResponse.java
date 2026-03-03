package com.debateai.dto;

public record AgentResponse(
        String agentName,
        String viewpoint,
        String content,
        long executionTimeMs,
        boolean successful,
        boolean timedOut,
        String errorMessage
) {
}
