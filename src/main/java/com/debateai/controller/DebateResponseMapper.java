package com.debateai.controller;

import com.debateai.dto.DebateResponseView;
import com.debateai.dto.DebateResult;
import com.debateai.service.TextSimilarityService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DebateResponseMapper {

    private static final String OPTIMIST_LABEL = "Optimist Agent";
    private static final String SKEPTIC_LABEL = "Skeptic Agent";
    private static final String RISK_LABEL = "Risk Analyst Agent";

    private final TextSimilarityService textSimilarityService;

    public DebateResponseMapper(TextSimilarityService textSimilarityService) {
        this.textSimilarityService = textSimilarityService;
    }

    public DebateResponseView from(DebateResult result) {
        String topic = sanitize(result.topic(), "");
        String optimist = sanitize(result.optimistView(), "No optimist response available.");
        String skeptic = sanitize(result.skepticView(), "No skeptic response available.");
        String risk = sanitize(result.riskAnalysis(), "No risk analyst response available.");
        String moderator = sanitize(result.finalDecision(), "No moderator summary available.");

        Map<String, String> corpus = new LinkedHashMap<>();
        corpus.put(OPTIMIST_LABEL, optimist);
        corpus.put(SKEPTIC_LABEL, skeptic);
        corpus.put(RISK_LABEL, risk);

        TextSimilarityService.SimilarityAnalysis analysis = textSimilarityService.analyzePairwiseSimilarity(corpus);

        Map<String, Double> matrix = new LinkedHashMap<>();
        matrix.put("optimist_vs_skeptic", round2(analysis.similarityBetween(OPTIMIST_LABEL, SKEPTIC_LABEL)));
        matrix.put("optimist_vs_risk", round2(analysis.similarityBetween(OPTIMIST_LABEL, RISK_LABEL)));
        matrix.put("skeptic_vs_risk", round2(analysis.similarityBetween(SKEPTIC_LABEL, RISK_LABEL)));

        return new DebateResponseView(
                topic,
                optimist,
                skeptic,
                risk,
                moderator,
                clamp(round2(result.confidenceScore())),
                matrix
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
