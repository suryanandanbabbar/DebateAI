package com.debateai.controller;

import com.debateai.dto.DebateResponseView;
import com.debateai.dto.DebateResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DebateResponseMapper {

    public DebateResponseMapper() {
    }

    public DebateResponseView from(DebateResult result) {
        String topic = sanitize(result.topic(), "");
        String optimist = sanitize(result.optimistView(), "No optimist analysis available.");
        String skeptic = sanitize(result.skepticView(), "No skeptic analysis available.");
        String risk = sanitize(result.riskAnalysis(), "No risk analysis available.");
        String winner = sanitize(result.winner(), "Unknown");
        String decisionReasoning = sanitize(result.finalDecision(), "No moderator summary available.");

        return new DebateResponseView(
                topic,
                optimist,
                skeptic,
                risk,
                winner,
                decisionReasoning,
                clamp(round2(result.confidenceScore())),
                null
        );
    }

    public DebateResponseView fromAborted(String topic, String errorMessage) {
        return new DebateResponseView(
                sanitize(topic, ""),
                null,
                null,
                null,
                null,
                null,
                null,
                sanitize(errorMessage, "Debate failed due to unavailable agent outputs")
        );
    }

    private String sanitize(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
