package com.debateai.dto;

public record DebateResult(
        String topic,
        String winner,
        String optimistView,
        String skepticView,
        String riskAnalysis,
        String finalDecision,
        double confidenceScore
) {
}
