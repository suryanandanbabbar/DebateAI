package com.debateai.controller;

import com.debateai.dto.DebateResponseView;
import com.debateai.dto.DebateResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DebateResponseMapper {

    private static final String DEFAULT_OPTIMIST = "No optimist analysis available.";
    private static final String DEFAULT_SKEPTIC = "No skeptic analysis available.";
    private static final String DEFAULT_RISK = "No risk analysis available.";
    private static final String DEFAULT_WINNER = "Unknown";
    private static final String DEFAULT_SUMMARY = "No moderator summary available.";
    private static final String DEFAULT_ERROR = "Debate failed due to unavailable agent outputs";

    public DebateResponseMapper() {}

    /**
     * Converts internal DebateResult to a clean API response view.
     * Ensures the response always contains structured values.
     */
    public DebateResponseView from(DebateResult result) {

        if (result == null) {
            return fromAborted("", DEFAULT_ERROR);
        }

        String topic = sanitize(result.topic(), "");
        String optimist = sanitize(result.optimistView(), DEFAULT_OPTIMIST);
        String skeptic = sanitize(result.skepticView(), DEFAULT_SKEPTIC);
        String risk = sanitize(result.riskAnalysis(), DEFAULT_RISK);
        String winner = sanitize(result.winner(), DEFAULT_WINNER);
        String decisionReasoning = sanitize(result.finalDecision(), DEFAULT_SUMMARY);

        Double confidence = clamp(round2(result.confidenceScore()));

        return new DebateResponseView(
                topic,
                optimist,
                skeptic,
                risk,
                winner,
                decisionReasoning,
                confidence,
                null
        );
    }

    /**
     * Builds a response when debate execution fails.
     */
    public DebateResponseView fromAborted(String topic, String errorMessage) {

        return new DebateResponseView(
                sanitize(topic, ""),
                null,
                null,
                null,
                null,
                null,
                0.0,
                sanitize(errorMessage, DEFAULT_ERROR)
        );
    }

    /**
     * Ensures non-empty strings with fallback values.
     */
    private String sanitize(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * Restricts confidence score between 0 and 1.
     */
    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    /**
     * Rounds value to 2 decimal places.
     */
    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}