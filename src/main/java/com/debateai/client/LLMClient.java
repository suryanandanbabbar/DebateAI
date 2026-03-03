package com.debateai.client;

import com.debateai.config.AppConfig;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);

    private final RestClient restClient;
    private final AppConfig.DebateProperties properties;

    public LLMClient(RestClient llmRestClient, AppConfig.DebateProperties properties) {
        this.restClient = llmRestClient;
        this.properties = properties;
    }

    public String generate(String agentName, String personaPrompt, String topic) {
        if (properties.llm().mockMode()) {
            return generateMock(agentName, topic);
        }
        return callRemoteModel(agentName, personaPrompt, topic);
    }

    private String callRemoteModel(String agentName, String personaPrompt, String topic) {
        if (!StringUtils.hasText(properties.llm().baseUrl())
                && !properties.llm().chatPath().startsWith("http")) {
            throw new IllegalStateException("LLM base URL is required when mock-mode is false");
        }

        Map<String, Object> requestPayload = Map.of(
                "model", properties.llm().model(),
                "temperature", properties.llm().temperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", personaPrompt),
                        Map.of("role", "user", "content", "Debate topic: " + topic + "\nRespond in 4-6 concise sentences.")
                )
        );

        try {
            Map<?, ?> responseBody = restClient.post()
                    .uri(properties.llm().chatPath())
                    .headers(headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        if (StringUtils.hasText(properties.llm().apiKey())) {
                            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.llm().apiKey());
                        }
                    })
                    .body(requestPayload)
                    .retrieve()
                    .body(Map.class);

            String content = extractContent(responseBody);
            log.debug("Received remote LLM response for {}", agentName);
            return content;
        } catch (RestClientException ex) {
            throw new IllegalStateException("LLM call failed for " + agentName, ex);
        }
    }

    private String extractContent(Map<?, ?> responseBody) {
        if (responseBody == null) {
            throw new IllegalStateException("Empty response from LLM provider");
        }

        Object choicesObject = responseBody.get("choices");
        if (choicesObject instanceof List<?> choices && !choices.isEmpty()) {
            Object firstChoice = choices.get(0);
            if (firstChoice instanceof Map<?, ?> choiceMap) {
                Object messageObject = choiceMap.get("message");
                if (messageObject instanceof Map<?, ?> messageMap) {
                    Object contentObject = messageMap.get("content");
                    if (contentObject instanceof String text && StringUtils.hasText(text)) {
                        return text.trim();
                    }
                }
            }
        }

        Object outputObject = responseBody.get("output");
        if (outputObject instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }

        throw new IllegalStateException("Unable to parse LLM response payload");
    }

    private String generateMock(String agentName, String topic) {
        return switch (agentName.toLowerCase()) {
            case "optimist agent" ->
                    "Microservices can accelerate feature delivery for " + topic
                            + " by enabling independent team ownership, faster experimentation, and selective scaling. "
                            + "A modular domain split can improve product velocity and reduce release coupling. "
                            + "With platform guardrails, observability, and API governance, architecture debt can remain controllable.";
            case "skeptic agent" ->
                    "Adopting microservices early for " + topic
                            + " risks over-engineering, higher operational complexity, and slower onboarding. "
                            + "Distributed failure modes, network latency, and version drift can harm reliability in small teams. "
                            + "A monolith-first approach often preserves focus until domain boundaries are stable.";
            case "risk analyst agent" ->
                    "Primary risks include security misconfiguration across services, inconsistent compliance controls, and rising cloud spend. "
                            + "Operational risk also increases through incident triage complexity and dependency blast radius. "
                            + "Mitigation requires standardized security policies, centralized telemetry, staged rollout, and cost budgets tied to SLOs.";
            default ->
                    "Moderated synthesis: prioritize phased adoption, clear service boundaries, and strong engineering governance.";
        };
    }
}
