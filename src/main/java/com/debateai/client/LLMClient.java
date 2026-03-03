package com.debateai.client;

import com.debateai.config.AppConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        String normalizedTopic = StringUtils.hasText(topic) ? topic.trim() : "the provided topic";
        String lowerTopic = normalizedTopic.toLowerCase(Locale.ROOT);

        String domainFocus = domainFocus(lowerTopic);
        String decisionFrame = decisionFrame(lowerTopic);
        List<String> keySignals = extractSignals(lowerTopic);

        return switch (agentName.toLowerCase(Locale.ROOT)) {
            case "optimist agent" -> buildOptimistResponse(normalizedTopic, domainFocus, decisionFrame, keySignals);
            case "skeptic agent" -> buildSkepticResponse(normalizedTopic, domainFocus, decisionFrame, keySignals);
            case "risk analyst agent" -> buildRiskResponse(normalizedTopic, domainFocus, decisionFrame, keySignals);
            default -> "This synthesis evaluates \"" + normalizedTopic + "\" using a balanced lens across "
                    + domainFocus + " with explicit trade-off handling.";
        };
    }

    private String buildOptimistResponse(String topic, String domainFocus, String decisionFrame, List<String> keySignals) {
        return "From a positive perspective, \"" + topic + "\" has strong upside if executed with clear goals. "
                + decisionFrame + " It can unlock gains in " + domainFocus + ", especially when decisions are tested with real users. "
                + "Early iteration and measurable checkpoints can compound value over time. "
                + "Priority signals to exploit are " + String.join(", ", keySignals) + ".";
    }

    private String buildSkepticResponse(String topic, String domainFocus, String decisionFrame, List<String> keySignals) {
        return "A critical view of \"" + topic + "\" suggests meaningful downsides if assumptions are not validated. "
                + decisionFrame + " Trade-offs may emerge around " + domainFocus + ", where perceived gains can hide maintenance cost. "
                + "Without clear evaluation criteria, teams may optimize for short-term convenience rather than durable outcomes. "
                + "The most fragile assumptions involve " + String.join(", ", keySignals) + ".";
    }

    private String buildRiskResponse(String topic, String domainFocus, String decisionFrame, List<String> keySignals) {
        return "Risk analysis for \"" + topic + "\" should quantify operational, financial, and technical exposure first. "
                + decisionFrame + " Key risk surfaces include " + domainFocus + ", plus failure scenarios tied to "
                + String.join(", ", keySignals) + ". "
                + "Mitigation should include staged rollout, explicit guardrails, and predefined fallback paths if outcomes regress.";
    }

    private String domainFocus(String lowerTopic) {
        if (containsAny(lowerTopic, "chatgpt", "claude", "llm", "ai", "language model")) {
            return "answer quality, reasoning reliability, latency, safety controls, context handling, and ecosystem fit";
        }
        if (containsAny(lowerTopic, "microservice", "microservices", "monolith", "distributed", "kubernetes")) {
            return "deployment complexity, service boundaries, observability maturity, reliability, and team autonomy";
        }
        if (containsAny(lowerTopic, "database", "sql", "nosql", "postgres", "mysql", "redis")) {
            return "consistency guarantees, query performance, scaling limits, and operational resilience";
        }
        if (containsAny(lowerTopic, "cloud", "aws", "azure", "gcp", "serverless")) {
            return "cost elasticity, platform lock-in, reliability posture, and operational governance";
        }
        if (containsAny(lowerTopic, "hiring", "team", "developer", "engineer", "staff")) {
            return "talent quality, onboarding speed, productivity variance, and retention risk";
        }
        return "cost, reliability, maintainability, user impact, and long-term strategic flexibility";
    }

    private String decisionFrame(String lowerTopic) {
        if (containsAny(lowerTopic, " or ", " vs ", " versus ", "compare", "comparison")) {
            return "Because this is a comparison problem, use weighted criteria and score each option before deciding.";
        }
        if (lowerTopic.startsWith("should i") || lowerTopic.startsWith("should we") || lowerTopic.contains("should ")) {
            return "Because this is a go/no-go decision, define success metrics and decision thresholds upfront.";
        }
        if (lowerTopic.startsWith("how ")) {
            return "Because this is an execution question, evaluate implementation sequence and measurable milestones.";
        }
        return "Use explicit objectives and measurable outcomes to keep analysis grounded in evidence.";
    }

    private List<String> extractSignals(String lowerTopic) {
        Set<String> ignored = Set.of("should", "use", "with", "from", "into", "this", "that", "have", "would");
        String cleaned = lowerTopic.replaceAll("[^a-z0-9\\s]", " ").trim();
        if (!StringUtils.hasText(cleaned)) {
            return List.of("scope clarity", "cost boundary", "quality target");
        }

        List<String> tokens = Arrays.stream(cleaned.split("\\s+"))
                .filter(token -> token.length() >= 4)
                .filter(token -> !ignored.contains(token))
                .distinct()
                .limit(4)
                .toList();

        if (tokens.isEmpty()) {
            return List.of("scope clarity", "cost boundary", "quality target");
        }

        List<String> signals = new ArrayList<>(tokens);
        while (signals.size() < 3) {
            signals.add(signals.get(signals.size() - 1));
        }
        return signals;
    }

    private boolean containsAny(String source, String... candidates) {
        for (String candidate : candidates) {
            if (source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
