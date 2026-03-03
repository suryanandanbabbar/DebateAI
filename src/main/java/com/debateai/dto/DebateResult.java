package com.debateai.dto;

public record DebateResult(
        String optimistView,
        String skepticView,
        String riskAnalysis,
        String finalDecision,
        double confidenceScore
) {
}
