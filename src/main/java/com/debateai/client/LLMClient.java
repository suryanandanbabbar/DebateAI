package com.debateai.client;

public interface LLMClient {

    String provider();

    LLMGenerationResponse generate(LLMGenerationRequest request);
}
