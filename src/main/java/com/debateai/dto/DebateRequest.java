package com.debateai.dto;

import jakarta.validation.constraints.NotBlank;

public record DebateRequest(
        @NotBlank(message = "topic must not be blank") String topic
) {
}
