package com.debateai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DebateResponseView(
        String topic,
        Analysis analysis,
        String decision,
        double confidence
) {
    public record Analysis(
            String optimist,
            String skeptic,
            String risk
    ) {
    }
}
