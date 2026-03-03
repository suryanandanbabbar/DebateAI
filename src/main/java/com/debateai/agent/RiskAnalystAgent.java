package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.dto.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RiskAnalystAgent implements DebateAgent {

    private static final Logger log = LoggerFactory.getLogger(RiskAnalystAgent.class);
    private static final String AGENT_NAME = "Risk Analyst Agent";
    private static final String VIEWPOINT = "risk-analyst";

    private final LLMClient llmClient;

    public RiskAnalystAgent(LLMClient llmClient) {
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
        return "You are a Risk Analyst Agent.\n"
                + "Analyze the following topic from operational, financial, and technical risk perspectives.\n\n"
                + "Topic:\n"
                + topic;
    }
}
