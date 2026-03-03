package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.client.LLMExecutionConfig;
import com.debateai.client.LLMGenerationRequest;
import com.debateai.client.LLMGenerationResponse;
import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateResult;
import com.debateai.service.TextSimilarityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String OPTIMIST_LABEL = "Optimist Agent";
    private static final String SKEPTIC_LABEL = "Skeptic Agent";
    private static final String RISK_LABEL = "Risk Analyst Agent";

    private final TextSimilarityService textSimilarityService;

    public ModeratorAgent(TextSimilarityService textSimilarityService) {
        this.textSimilarityService = textSimilarityService;
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
    public AgentResponse generate(String topic, LLMClient llmClient, LLMExecutionConfig config) {
        return new AgentResponse(agentName(), viewpoint(),
                "Moderator consolidates other agent outputs and is not called directly.",
                0L, true, false, null);
    }

    @Override
    public DebateResult moderate(String topic,
                                 List<AgentResponse> responses,
                                 LLMClient llmClient,
                                 LLMExecutionConfig config) {
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

        Map<String, String> similarityCorpus = new LinkedHashMap<>();
        similarityCorpus.put(OPTIMIST_LABEL, optimistView);
        similarityCorpus.put(SKEPTIC_LABEL, skepticView);
        similarityCorpus.put(RISK_LABEL, riskView);

        TextSimilarityService.SimilarityAnalysis similarityAnalysis =
                textSimilarityService.analyzePairwiseSimilarity(similarityCorpus);

        double optimistVsSkeptic = similarityAnalysis.similarityBetween(OPTIMIST_LABEL, SKEPTIC_LABEL);
        double optimistVsRisk = similarityAnalysis.similarityBetween(OPTIMIST_LABEL, RISK_LABEL);
        double skepticVsRisk = similarityAnalysis.similarityBetween(SKEPTIC_LABEL, RISK_LABEL);

        double averageSimilarity = similarityAnalysis.averageSimilarity();
        double confidenceScore = calibrateConfidenceScore(averageSimilarity);

        log.info("Semantic similarity - Optimist vs Skeptic: {}",
                String.format(Locale.US, "%.4f", optimistVsSkeptic));
        log.info("Semantic similarity - Optimist vs Risk: {}",
                String.format(Locale.US, "%.4f", optimistVsRisk));
        log.info("Semantic similarity - Skeptic vs Risk: {}",
                String.format(Locale.US, "%.4f", skepticVsRisk));
        log.info("Semantic convergence - average similarity: {}",
                String.format(Locale.US, "%.4f", averageSimilarity));
        log.info("Confidence score calibrated from semantic convergence: {}",
                String.format(Locale.US, "%.2f", confidenceScore));

        List<String> agreements = detectAgreements(responses);
        List<String> conflicts = detectConflicts(responses, similarityAnalysis);
        String riskSummary = summarizeRisk(riskView);

        String recommendation = buildRecommendation(topic, agreements, conflicts, riskSummary, responses, llmClient, config);
        String finalDecision = "Key agreements: " + String.join(", ", agreements)
                + "\nMajor conflicts: " + String.join(", ", conflicts)
                + "\nRisk summary: " + riskSummary
                + "\nFinal balanced recommendation: " + recommendation;

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("{} completed synthesis in {} ms with average semantic similarity {}",
                agentName(), durationMs, String.format(Locale.US, "%.4f", averageSimilarity));

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
                .map(textSimilarityService::tokenize)
                .map(HashSet::new)
                .forEach(tokens -> tokens.forEach(token -> tokenFrequency.merge(token, 1, Integer::sum)));

        List<String> agreements = tokenFrequency.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(4)
                .map(entry -> "shared focus on " + entry.getKey())
                .toList();

        if (agreements.isEmpty()) {
            return List.of("need for stronger evidence", "need for practical rollout controls");
        }
        return agreements;
    }

    private List<String> detectConflicts(List<AgentResponse> responses,
                                         TextSimilarityService.SimilarityAnalysis similarityAnalysis) {
        List<String> conflicts = new ArrayList<>();

        for (TextSimilarityService.PairwiseSimilarity pairwise : similarityAnalysis.pairwiseSimilarities()) {
            if (pairwise.similarity() < 0.35d) {
                conflicts.add(pairwise.leftLabel() + " and " + pairwise.rightLabel()
                        + " diverge on expected value versus downside exposure");
            }
        }

        responses.stream()
                .filter(response -> !response.successful())
                .forEach(response -> conflicts.add(response.agentName() + " produced low-confidence output due to "
                        + (response.timedOut() ? "timeout" : "processing failure")));

        if (conflicts.isEmpty()) {
            conflicts.add("weighting of upside speed versus control depth");
        }

        return conflicts.stream().limit(4).toList();
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
                                       LLMClient llmClient,
                                       LLMExecutionConfig config) {
        String userPrompt = "Topic: " + topic
                + "\n\nAgent outputs:\n" + responses.stream()
                .map(response -> "- " + response.agentName() + ": " + response.content())
                .collect(Collectors.joining("\n"))
                + "\n\nAgreements: " + String.join("; ", agreements)
                + "\nConflicts: " + String.join("; ", conflicts)
                + "\nRisk summary: " + riskSummary
                + "\n\nProduce a balanced recommendation in 4-6 sentences.";

        String systemPrompt = "You are the Moderator Agent in a structured AI debate. "
                + "Synthesize multiple viewpoints into one balanced, concrete recommendation.";

        LLMGenerationRequest request = new LLMGenerationRequest(
                config.model(),
                config.temperature(),
                systemPrompt,
                userPrompt,
                config.timeoutMillis(),
                config.maxAttempts()
        );

        try {
            LLMGenerationResponse response = llmClient.generate(request);
            return response.content();
        } catch (RuntimeException ex) {
            log.warn("Moderator synthesis failed. Falling back to deterministic summary.", ex);
            return "Prioritize a staged decision with explicit criteria, compare outcomes with measurable evidence, "
                    + "and select the option that maintains quality while controlling operational risk.";
        }
    }

    private double calibrateConfidenceScore(double averageSimilarity) {
        double calibrated = 0.3d + (0.7d * averageSimilarity);
        double clamped = clamp(calibrated, 0.0d, 1.0d);
        return Math.round(clamped * 100.0d) / 100.0d;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
