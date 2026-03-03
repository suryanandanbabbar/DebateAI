package com.debateai.dto;

public record DebateResult(
        String topic,
        String optimistView,
        String skepticView,
        String riskAnalysis,
        String finalDecision,
        double confidenceScore
) {
}
