package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.client.LLMExecutionConfig;
import com.debateai.client.LLMGenerationRequest;
import com.debateai.client.LLMGenerationResponse;
import com.debateai.dto.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SkepticAgent implements DebateAgent {

    private static final Logger log = LoggerFactory.getLogger(SkepticAgent.class);
    private static final String AGENT_NAME = "Skeptic Agent";
    private static final String VIEWPOINT = "skeptic";

    @Override
    public String agentName() {
        return AGENT_NAME;
    }

    @Override
    public String viewpoint() {
        return VIEWPOINT;
    }

    @Override
    public AgentResponse generate(String topic, LLMClient llmClient, LLMExecutionConfig config) {
        long start = System.nanoTime();
        try {
            LLMGenerationRequest request = new LLMGenerationRequest(
                    config.model(),
                    config.temperature(),
                    buildSystemPrompt(),
                    "Topic: " + topic,
                    config.timeoutMillis(),
                    config.maxAttempts(),
                    350
            );
            LLMGenerationResponse response = llmClient.generate(request);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("{} completed in {} ms", agentName(), durationMs);
            return new AgentResponse(agentName(), viewpoint(), response.content(), durationMs, true, false, null);
        } catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("{} failed in {} ms", agentName(), durationMs, ex);
            throw ex;
        }
    }

    private String buildSystemPrompt() {
        return "You are a Skeptic agent.\n"
                + "Respond in a maximum of 180 words.\n"
                + "Use bullet points only.\n"
                + "No introductions.\n"
                + "No metaphors.\n"
                + "No repetition.\n"
                + "Be direct and analytical.";
    }
}
