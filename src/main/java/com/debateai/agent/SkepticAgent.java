package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.dto.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SkepticAgent implements DebateAgent {

    private static final Logger log = LoggerFactory.getLogger(SkepticAgent.class);
    private static final String AGENT_NAME = "Skeptic Agent";
    private static final String VIEWPOINT = "skeptic";

    private final LLMClient llmClient;

    public SkepticAgent(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String agentName() {
        return AGENT_NAME;
    }

    @Override
    public String viewpoint() {
        return VIEWPOINT;
    }

    @Override
    public AgentResponse generate(String topic) {
        long start = System.nanoTime();
        try {
            String content = llmClient.generate(agentName(), buildPersonaPrompt(topic), topic);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("{} completed in {} ms", agentName(), durationMs);
            return new AgentResponse(agentName(), viewpoint(), content, durationMs, true, false, null);
        } catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("{} failed in {} ms", agentName(), durationMs, ex);
            throw ex;
        }
    }

    private String buildPersonaPrompt(String topic) {
        return "You are a Skeptic Agent.\n"
                + "Critically analyze the following topic.\n"
                + "Focus on weaknesses, trade-offs, downsides, and limitations.\n\n"
                + "Topic:\n"
                + topic;
    }
}
