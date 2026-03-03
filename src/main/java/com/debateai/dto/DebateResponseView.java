package com.debateai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DebateResponseView(
        String topic,
        String winner,
        String reasoning,
        double confidence
) {
}
