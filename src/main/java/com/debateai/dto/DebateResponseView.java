package com.debateai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DebateResponseView(
        String topic,
        String optimist,
        String skeptic,
        String risk,
        String winner,
        String decisionReasoning,
        Double confidence,
        String error
) {
}
