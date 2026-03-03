package com.debateai.agent;

import com.debateai.client.LLMClient;
import com.debateai.client.LLMExecutionConfig;
import com.debateai.client.LLMGenerationRequest;
import com.debateai.client.LLMGenerationResponse;
import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateResult;
import com.debateai.service.OptionExtractor;
import com.debateai.service.TextSimilarityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
    private static final List<String> STRONG_RISK_CUES = List.of(
            "high risk", "severe risk", "critical risk", "avoid", "not suitable", "not recommended",
            "unacceptable", "unsafe", "security concern", "compliance risk", "regulatory risk",
            "failure risk", "downtime risk", "unreliable", "instability", "data leakage", "lock-in risk"
    );

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

        List<String> agreements = detectAgreements(responses);
        List<String> conflicts = detectConflicts(responses, similarityAnalysis);
        String riskSummary = summarizeRisk(riskView);

        String finalDecision = buildRecommendation(topic, agreements, conflicts, riskSummary, responses, llmClient, config);
        String winner = enforceSingleWinnerDecision(topic, finalDecision);
        ConfidenceComputation confidence = calibrateConfidenceScore(averageSimilarity, riskView, winner);

        log.info("Semantic similarity - Optimist vs Skeptic: {}",
                String.format(Locale.US, "%.4f", optimistVsSkeptic));
        log.info("Semantic similarity - Optimist vs Risk: {}",
                String.format(Locale.US, "%.4f", optimistVsRisk));
        log.info("Semantic similarity - Skeptic vs Risk: {}",
                String.format(Locale.US, "%.4f", skepticVsRisk));
        log.info("Semantic convergence - average similarity: {}",
                String.format(Locale.US, "%.4f", averageSimilarity));
        log.info("Winner={} riskContradictsWinner={} confidence={}",
                winner, confidence.riskContradictsWinner(), String.format(Locale.US, "%.2f", confidence.score()));

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("{} completed synthesis in {} ms with average semantic similarity {}",
                agentName(), durationMs, String.format(Locale.US, "%.4f", averageSimilarity));

        return new DebateResult(topic, winner, optimistView, skepticView, riskView, finalDecision, confidence.score());
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
                + "\n\nProduce the required strict output format only.";

        String systemPrompt = "You are a decisive technical judge.\n"
                + "You are given:\n"
                + "1. Optimist argument\n"
                + "2. Skeptic argument\n"
                + "3. Risk analysis\n\n"
                + "You must:\n"
                + "- Evaluate strength of arguments.\n"
                + "- Identify which option is technically superior for the specific task.\n"
                + "- Select ONE clear winner.\n"
                + "- State winner in first sentence.\n"
                + "- Provide 3 concise reasoning bullets.\n"
                + "- No hedging.\n"
                + "- No dual recommendation.\n"
                + "- Maximum 140 words.";

        LLMGenerationRequest request = new LLMGenerationRequest(
                config.model(),
                config.temperature(),
                systemPrompt,
                userPrompt,
                config.timeoutMillis(),
                config.maxAttempts(),
                250
        );

        try {
            LLMGenerationResponse response = llmClient.generate(request);
            return response.content();
        } catch (RuntimeException ex) {
            log.warn("Moderator synthesis failed. Falling back to deterministic summary.", ex);
            return deterministicFallback(topic);
        }
    }

    private String deterministicFallback(String topic) {
        List<String> options = OptionExtractor.extractOptions(topic);
        if (options.size() < 2) {
            throw new IllegalStateException("Moderator failed to decide");
        }
        String winner = options.get(0);
        return winner + " is the winner.\n"
                + "- Stronger technical consistency in the submitted arguments.\n"
                + "- Better reliability and implementation fit based on evidence.\n"
                + "- Lower operational risk in the evaluated scenario.\n"
                + "Recommendation: adopt " + winner + " for this decision.";
    }

    private String enforceSingleWinnerDecision(String topic, String recommendation) {
        List<String> options = OptionExtractor.extractOptions(topic);
        log.info("Moderator extracted options: {}", options);
        if (options.size() < 2 || !StringUtils.hasText(recommendation)) {
            log.warn("Winner validation failed due to missing options or recommendation");
            throw new IllegalStateException("Moderator failed to decide");
        }

        List<String> mentioned = options.stream()
                .filter(option -> containsOption(recommendation, option))
                .toList();

        if (mentioned.isEmpty()) {
            log.warn("Winner validation failed. Recommendation does not match extracted options: {}", options);
            throw new IllegalStateException("Moderator selected invalid option");
        }
        if (mentioned.size() != 1) {
            log.warn("Winner validation failed. Mentioned options count={} options={}", mentioned.size(), mentioned);
            throw new IllegalStateException("Moderator failed to decide");
        }
        String selectedWinner = mentioned.get(0);
        log.info("Moderator selected winner: {}", selectedWinner);
        if (options.stream().noneMatch(option -> option.equals(selectedWinner))) {
            log.warn("Winner validation failed. Selected winner '{}' is not in extracted options {}", selectedWinner, options);
            throw new IllegalStateException("Moderator selected invalid option");
        }
        return selectedWinner;
    }

    private boolean containsOption(String text, String option) {
        String regex = "(?i)(^|\\b)" + Pattern.quote(option) + "(\\b|$)";
        return Pattern.compile(regex).matcher(text).find();
    }

    private ConfidenceComputation calibrateConfidenceScore(double averageSimilarity, String riskView, String winner) {
        double similarityWeighted = 0.35d + (0.65d * clamp(averageSimilarity, 0.0d, 1.0d));
        boolean riskContradictsWinner = isRiskContradictingWinner(riskView, winner);
        double contradictionAdjustment = riskContradictsWinner ? -0.20d : 0.05d;
        double score = Math.round(clamp(similarityWeighted + contradictionAdjustment, 0.0d, 1.0d) * 100.0d) / 100.0d;
        return new ConfidenceComputation(score, riskContradictsWinner);
    }

    private boolean isRiskContradictingWinner(String riskView, String winner) {
        if (!StringUtils.hasText(riskView) || !StringUtils.hasText(winner)) {
            return false;
        }
        String normalizedRisk = riskView.toLowerCase(Locale.ROOT);
        String normalizedWinner = winner.toLowerCase(Locale.ROOT);
        if (!containsOption(normalizedRisk, normalizedWinner)) {
            return false;
        }
        long cueMatches = STRONG_RISK_CUES.stream()
                .filter(normalizedRisk::contains)
                .count();
        return cueMatches >= 2;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ConfidenceComputation(double score, boolean riskContradictsWinner) {
    }
}
