package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.config.AppConfig;
import com.debateai.dto.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OptimistAgent implements DebateAgent {

    private static final Logger log = LoggerFactory.getLogger(OptimistAgent.class);
    private static final String AGENT_NAME = "Optimist Agent";
    private static final String VIEWPOINT = "optimist";

    private final LLMClient llmClient;
    private final String personaPrompt;

    public OptimistAgent(LLMClient llmClient, AppConfig.DebateProperties properties) {
        this.llmClient = llmClient;
        this.personaPrompt = properties.prompts().optimist();
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
            String content = llmClient.generate(agentName(), personaPrompt, topic);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("{} completed in {} ms", agentName(), durationMs);
            return new AgentResponse(agentName(), viewpoint(), content, durationMs, true, false, null);
        } catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("{} failed in {} ms", agentName(), durationMs, ex);
            throw ex;
        }
    }
}
