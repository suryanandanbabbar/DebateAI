package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.config.AppConfig;
import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ModeratorAgent implements DebateAgent {

    private static final Logger log = LoggerFactory.getLogger(ModeratorAgent.class);
    private static final String AGENT_NAME = "Moderator Agent";
    private static final String VIEWPOINT = "moderator";

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "that", "with", "from", "this", "will", "into", "across", "have", "has", "are",
            "was", "were", "their", "there", "about", "should", "could", "would", "while", "where", "when", "which",
            "also", "than", "then", "they", "them", "your", "you", "our", "out", "all", "not", "can", "may", "use"
    );

    private final LLMClient llmClient;
    private final String moderatorPrompt;

    public ModeratorAgent(LLMClient llmClient, AppConfig.DebateProperties properties) {
        this.llmClient = llmClient;
        this.moderatorPrompt = properties.prompts().moderator();
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
    public boolean isModerator() {
        return true;
    }

    @Override
    public AgentResponse generate(String topic) {
        return new AgentResponse(agentName(), viewpoint(),
                "Moderator consolidates other agent outputs and is not called directly.",
                0L, true, false, null);
    }

    @Override
    public DebateResult moderate(String topic, List<AgentResponse> responses) {
        long start = System.nanoTime();

        Map<String, AgentResponse> byViewpoint = responses.stream()
                .collect(Collectors.toMap(
                        response -> response.viewpoint().toLowerCase(Locale.ROOT),
                        response -> response,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        String optimistView = viewOrFallback(byViewpoint.get("optimist"), "Optimist response unavailable.");
        String skepticView = viewOrFallback(byViewpoint.get("skeptic"), "Skeptic response unavailable.");
        String riskView = viewOrFallback(byViewpoint.get("risk-analyst"), "Risk analysis unavailable.");

        List<String> agreements = detectAgreements(responses);
        List<String> conflicts = detectConflicts(responses);
        double disagreementMetric = calculateDisagreementMetric(responses);
        double confidenceScore = calculateConfidenceScore(disagreementMetric, responses, agreements.size());

        String riskSummary = summarizeRisk(riskView);
        String recommendation = buildRecommendation(topic, agreements, conflicts, riskSummary, responses, disagreementMetric);
        String finalDecision = "Key agreements: " + String.join(", ", agreements)
                + "\nMajor conflicts: " + String.join(", ", conflicts)
                + "\nRisk summary: " + riskSummary
                + "\nFinal balanced recommendation: " + recommendation;

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("{} completed synthesis in {} ms with disagreement metric {}",
                agentName(), durationMs, String.format(Locale.US, "%.2f", disagreementMetric));

        return new DebateResult(optimistView, skepticView, riskView, finalDecision, confidenceScore);
    }

    private String viewOrFallback(AgentResponse response, String fallback) {
        if (response == null) {
            return fallback;
        }
        if (!response.successful()) {
            if (response.timedOut()) {
                return response.agentName() + " timed out before producing a usable answer.";
            }
            return response.agentName() + " failed: " + response.errorMessage();
        }
        return response.content();
    }

    private List<String> detectAgreements(List<AgentResponse> responses) {
        Map<String, Integer> tokenFrequency = new HashMap<>();

        responses.stream()
                .filter(AgentResponse::successful)
                .map(AgentResponse::content)
                .map(this::tokenize)
                .forEach(tokens -> tokens.forEach(token -> tokenFrequency.merge(token, 1, Integer::sum)));

        List<String> agreements = tokenFrequency.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(4)
                .map(entry -> "shared focus on " + entry.getKey())
                .toList();

        if (agreements.isEmpty()) {
            return List.of("need for phased delivery", "need for stronger observability");
        }
        return agreements;
    }

    private List<String> detectConflicts(List<AgentResponse> responses) {
        List<String> conflicts = new ArrayList<>();

        for (int i = 0; i < responses.size(); i++) {
            for (int j = i + 1; j < responses.size(); j++) {
                AgentResponse left = responses.get(i);
                AgentResponse right = responses.get(j);
                if (!left.successful() || !right.successful()) {
                    continue;
                }
                double similarity = jaccardSimilarity(tokenize(left.content()), tokenize(right.content()));
                if (similarity < 0.35d) {
                    conflicts.add(left.agentName() + " and " + right.agentName()
                            + " diverge on complexity versus speed of adoption");
                }
            }
        }

        responses.stream()
                .filter(response -> !response.successful())
                .forEach(response -> conflicts.add(response.agentName() + " produced low-confidence output due to "
                        + (response.timedOut() ? "timeout" : "processing failure")));

        if (conflicts.isEmpty()) {
            conflicts.add("degree of architectural decomposition in early stages");
        }

        return conflicts.stream().limit(4).toList();
    }

    private double calculateDisagreementMetric(List<AgentResponse> responses) {
        List<Set<String>> tokenSets = responses.stream()
                .filter(AgentResponse::successful)
                .map(AgentResponse::content)
                .map(this::tokenize)
                .filter(tokens -> !tokens.isEmpty())
                .toList();

        if (tokenSets.size() < 2) {
            return 0.9d;
        }

        double pairwiseDisagreementTotal = 0.0d;
        int pairCount = 0;
        for (int i = 0; i < tokenSets.size(); i++) {
            for (int j = i + 1; j < tokenSets.size(); j++) {
                double similarity = jaccardSimilarity(tokenSets.get(i), tokenSets.get(j));
                pairwiseDisagreementTotal += (1.0d - similarity);
                pairCount++;
            }
        }

        double pairwiseDisagreement = pairCount == 0 ? 1.0d : pairwiseDisagreementTotal / pairCount;
        double keywordVariance = keywordVariance(tokenSets);

        return clamp((0.75d * pairwiseDisagreement) + (0.25d * keywordVariance), 0.0d, 1.0d);
    }

    private double keywordVariance(List<Set<String>> tokenSets) {
        Set<String> union = new HashSet<>();
        Set<String> intersection = null;

        for (Set<String> tokenSet : tokenSets) {
            union.addAll(tokenSet);
            if (intersection == null) {
                intersection = new HashSet<>(tokenSet);
            } else {
                intersection.retainAll(tokenSet);
            }
        }

        if (union.isEmpty()) {
            return 1.0d;
        }

        int intersectionSize = intersection == null ? 0 : intersection.size();
        double variance = (union.size() - intersectionSize) / (double) union.size();
        return clamp(variance, 0.0d, 1.0d);
    }

    private double calculateConfidenceScore(double disagreementMetric,
                                            List<AgentResponse> responses,
                                            int agreementSignals) {
        long failedResponses = responses.stream().filter(response -> !response.successful()).count();
        double base = 1.0d - disagreementMetric;
        double agreementBoost = Math.min(0.1d, agreementSignals * 0.02d);
        double failurePenalty = failedResponses * 0.15d;

        double confidence = clamp(base + agreementBoost - failurePenalty, 0.05d, 0.98d);
        return Math.round(confidence * 100.0d) / 100.0d;
    }

    private String summarizeRisk(String riskView) {
        if (!StringUtils.hasText(riskView)) {
            return "Insufficient risk details available.";
        }
        String[] sentences = riskView.split("(?<=[.!?])\\s+");
        if (sentences.length <= 2) {
            return riskView.trim();
        }
        return (sentences[0] + " " + sentences[1]).trim();
    }

    private String buildRecommendation(String topic,
                                       List<String> agreements,
                                       List<String> conflicts,
                                       String riskSummary,
                                       List<AgentResponse> responses,
                                       double disagreementMetric) {
        String synthesisPrompt = moderatorPrompt
                + "\nTopic: " + topic
                + "\nAgreements: " + String.join(", ", agreements)
                + "\nConflicts: " + String.join(", ", conflicts)
                + "\nRisk summary: " + riskSummary
                + "\nDisagreement metric: " + String.format(Locale.US, "%.2f", disagreementMetric)
                + "\nAgent outputs: " + responses.stream()
                .map(response -> response.agentName() + ": " + response.content())
                .collect(Collectors.joining("\n"));

        try {
            return llmClient.generate(agentName(), synthesisPrompt, topic);
        } catch (RuntimeException ex) {
            log.warn("Moderator LLM synthesis failed. Falling back to deterministic recommendation.", ex);
            return "Adopt a phased architecture strategy: begin with a modular monolith, define domain boundaries early, "
                    + "and extract high-volatility or scaling-critical domains into microservices only when operational maturity, "
                    + "security controls, and observability standards are proven.";
        }
    }

    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }

        return Arrays.stream(text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9 ]", " ")
                        .split("\\s+"))
                .filter(token -> token.length() > 3)
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0d;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);

        Set<String> union = new HashSet<>(left);
        union.addAll(right);

        if (union.isEmpty()) {
            return 0.0d;
        }
        return intersection.size() / (double) union.size();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
