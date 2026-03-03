package com.debateai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DebateResponseView(
        String topic,
        String optimistResponse,
        String skepticResponse,
        String riskAnalystResponse,
        String moderatorSummary,
        double confidenceScore,
        Map<String, Double> semanticSimilarityMatrix
) {
}
