package com.debateai.service;

import com.debateai.agent.DebateAgent;
import com.debateai.client.LLMClient;
import com.debateai.client.LLMClientFactory;
import com.debateai.client.LLMExecutionConfig;
import com.debateai.client.ProviderSwitcher;
import com.debateai.config.AppConfig;
import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateRequest;
import com.debateai.dto.DebateResult;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DebateService {

    private static final Logger log = LoggerFactory.getLogger(DebateService.class);

    private final List<DebateAgent> debateAgents;
    private final DebateAgent moderatorAgent;
    private final Executor debateExecutor;
    private final LLMClientFactory llmClientFactory;
    private final ProviderSwitcher providerSwitcher;
    private final AppConfig.DebateProperties properties;

    public DebateService(List<DebateAgent> availableAgents,
                         @Qualifier("debateExecutor") Executor debateExecutor,
                         LLMClientFactory llmClientFactory,
                         ProviderSwitcher providerSwitcher,
                         AppConfig.DebateProperties properties) {
        this.debateExecutor = debateExecutor;
        this.llmClientFactory = llmClientFactory;
        this.providerSwitcher = providerSwitcher;
        this.properties = properties;
        this.moderatorAgent = availableAgents.stream()
                .filter(DebateAgent::isModerator)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ModeratorAgent is required"));
        this.debateAgents = availableAgents.stream()
                .filter(agent -> !agent.isModerator())
                .toList();

        if (this.debateAgents.isEmpty()) {
            throw new IllegalStateException("At least one debate agent is required");
        }
    }

    public DebateResult runDebate(DebateRequest request) {
        String topic = request.topic().trim();
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("topic must not be blank");
        }

        log.info("Starting debate for topic: {}", topic);

        List<CompletableFuture<AgentResponse>> futures = debateAgents.stream()
                .map(agent -> executeAgent(agent, topic))
                .toList();

        List<AgentResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        ResolvedClientConfig moderatorConfig = resolveClientConfig("moderator");
        DebateResult result = moderatorAgent.moderate(topic, responses, moderatorConfig.client(), moderatorConfig.executionConfig());
        result = applyResultDiscipline(result);
        String winner = parseWinnerWithRegex(result.topic(), result.finalDecision());
        result = new DebateResult(
                result.topic(),
                winner,
                result.optimistView(),
                result.skepticView(),
                result.riskAnalysis(),
                result.finalDecision(),
                clamp(round2(result.confidenceScore()))
        );
        log.info("Debate completed with confidence score {}", result.confidenceScore());
        return result;
    }

    private CompletableFuture<AgentResponse> executeAgent(DebateAgent agent, String topic) {
        ResolvedClientConfig resolved = resolveClientConfig(agent.viewpoint());
        return CompletableFuture.supplyAsync(() -> executeWithRetry(agent, topic, resolved), debateExecutor)
                .completeOnTimeout(timeoutResponse(agent), properties.timeout().perAgentMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> failedResponse(agent, unwrap(throwable)));
    }

    private AgentResponse executeWithRetry(DebateAgent agent, String topic, ResolvedClientConfig resolved) {
        int maxAttempts = properties.retry().maxAttempts();
        boolean switchedOnce = false;
        ResolvedClientConfig active = resolved;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Dispatching {} using provider={} model={} temperature={}",
                        agent.agentName(), active.provider(), active.executionConfig().model(),
                        String.format(Locale.US, "%.2f", active.executionConfig().temperature()));
                AgentResponse response = agent.generate(topic, active.client(), active.executionConfig());
                return applyResponseDiscipline(response);
            } catch (RuntimeException ex) {
                if (!switchedOnce && providerSwitcher.isApiKeyMismatchError(ex)) {
                    String fallbackProvider = providerSwitcher.findAlternativeProvider(active.provider());
                    if (!fallbackProvider.equals(active.provider()) && llmClientFactory.supports(fallbackProvider)) {
                        log.warn("Provider {} failed due to API key mismatch; switching to {} and retrying once.",
                                active.provider(), fallbackProvider);
                        active = buildClientConfig(fallbackProvider, active.executionConfig().temperature());
                        switchedOnce = true;
                        AgentResponse response = agent.generate(topic, active.client(), active.executionConfig());
                        return applyResponseDiscipline(response);
                    }
                }
                if (attempt >= maxAttempts) {
                    throw ex;
                }
                long backoff = properties.retry().backoffMillis() * attempt;
                log.warn("{} failed on attempt {}/{}. Retrying in {} ms",
                        agent.agentName(), attempt, maxAttempts, backoff, ex);
                sleep(backoff);
            }
        }

        throw new IllegalStateException("Unexpected retry state for " + agent.agentName());
    }

    private ResolvedClientConfig resolveClientConfig(String viewpoint) {
        AppConfig.DebateProperties.Llm.AgentConfig agentConfig = switch (viewpoint.toLowerCase(Locale.ROOT)) {
            case "optimist" -> properties.llm().agents().optimist();
            case "skeptic" -> properties.llm().agents().skeptic();
            case "risk", "risk-analyst" -> properties.llm().agents().risk();
            case "moderator" -> properties.llm().agents().moderator();
            default -> throw new IllegalArgumentException("No LLM config for viewpoint: " + viewpoint);
        };

        String configuredProvider = properties.llm().provider().trim().toLowerCase(Locale.ROOT);
        String provider = providerSwitcher.resolveProviderByKeyFormat(configuredProvider);
        return buildClientConfig(provider, agentConfig.temperature());
    }

    private ResolvedClientConfig buildClientConfig(String provider, double temperature) {
        LLMClient client = llmClientFactory.getClient(provider);
        String model = resolveModel(provider);
        long timeoutMillis = "gemini".equals(provider)
                ? TimeUnit.SECONDS.toMillis(properties.llm().gemini().timeoutSeconds())
                : properties.llm().timeoutMillis();

        LLMExecutionConfig executionConfig = new LLMExecutionConfig(
                model,
                temperature,
                timeoutMillis,
                properties.llm().maxAttempts()
        );

        return new ResolvedClientConfig(provider, client, executionConfig);
    }

    private String resolveModel(String provider) {
        return switch (provider) {
            case "openai" -> properties.llm().providers().openai().model();
            case "anthropic" -> properties.llm().providers().anthropic().model();
            case "gemini" -> properties.llm().gemini().model();
            default -> throw new IllegalArgumentException("Unsupported provider for model resolution: " + provider);
        };
    }

    private AgentResponse timeoutResponse(DebateAgent agent) {
        long timeoutMs = properties.timeout().perAgentMillis();
        return new AgentResponse(
                agent.agentName(),
                agent.viewpoint(),
                "No response due to timeout.",
                timeoutMs,
                false,
                true,
                "Timed out after " + timeoutMs + " ms"
        );
    }

    private AgentResponse failedResponse(DebateAgent agent, Throwable throwable) {
        String message = throwable.getMessage() == null ? "Unknown error" : throwable.getMessage();
        return new AgentResponse(
                agent.agentName(),
                agent.viewpoint(),
                "No response due to processing failure.",
                0L,
                false,
                false,
                message
        );
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    private void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry backoff interrupted", ex);
        }
    }

    private record ResolvedClientConfig(
            String provider,
            LLMClient client,
            LLMExecutionConfig executionConfig
    ) {
    }

    private AgentResponse applyResponseDiscipline(AgentResponse response) {
        String disciplined = enforceLengthDiscipline(response.content(), response.viewpoint());
        return new AgentResponse(
                response.agentName(),
                response.viewpoint(),
                disciplined,
                response.executionTimeMs(),
                response.successful(),
                response.timedOut(),
                response.errorMessage()
        );
    }

    private DebateResult applyResultDiscipline(DebateResult result) {
        return new DebateResult(
                result.topic(),
                result.winner(),
                enforceLengthDiscipline(result.optimistView(), "optimist"),
                enforceLengthDiscipline(result.skepticView(), "skeptic"),
                enforceLengthDiscipline(result.riskAnalysis(), "risk-analyst"),
                enforceLengthDiscipline(result.finalDecision(), "moderator"),
                result.confidenceScore()
        );
    }

    private String enforceLengthDiscipline(String text, String viewpoint) {
        if (!StringUtils.hasText(text)) {
            return text;
        }

        int maxWords = "moderator".equalsIgnoreCase(viewpoint) ? 140 : 180;
        String trimmedWords = limitWords(text.trim(), maxWords);
        if (trimmedWords.length() <= 1500) {
            return trimmedWords;
        }
        return truncateAtSentenceBoundary(trimmedWords, 1500);
    }

    private String limitWords(String text, int maxWords) {
        String[] words = Arrays.stream(text.split("\\s+"))
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (words.length <= maxWords) {
            return text;
        }
        return String.join(" ", Arrays.copyOf(words, maxWords));
    }

    private String truncateAtSentenceBoundary(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String candidate = text.substring(0, maxChars);
        int lastBoundary = Math.max(candidate.lastIndexOf('.'),
                Math.max(candidate.lastIndexOf('!'), candidate.lastIndexOf('?')));
        if (lastBoundary >= 0) {
            return candidate.substring(0, lastBoundary + 1).trim();
        }
        int lastSpace = candidate.lastIndexOf(' ');
        if (lastSpace > 0) {
            return candidate.substring(0, lastSpace).trim();
        }
        return candidate.trim();
    }

    private String parseWinnerWithRegex(String topic, String response) {
        if (!StringUtils.hasText(topic) || !StringUtils.hasText(response)) {
            throw new IllegalStateException("Moderator failed to decide");
        }

        List<String> options = extractOptionsFromTopic(topic);
        if (options.size() != 2) {
            throw new IllegalStateException("Moderator failed to decide");
        }

        int mentionCount = 0;
        String winner = null;
        for (String option : options) {
            Pattern pattern = Pattern.compile("(?i)(^|\\b)" + Pattern.quote(option) + "(\\b|$)");
            Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                mentionCount++;
                winner = option;
            }
        }

        if (mentionCount != 1 || !StringUtils.hasText(winner)) {
            throw new IllegalStateException("Moderator failed to decide");
        }
        return winner;
    }

    private List<String> extractOptionsFromTopic(String topic) {
        String cleaned = topic.trim().replaceAll("[?!.]+$", "");
        String lower = cleaned.toLowerCase(Locale.ROOT);

        for (String delimiter : List.of(" versus ", " vs ", " or ")) {
            int idx = lower.indexOf(delimiter);
            if (idx > 0) {
                String left = normalizeOption(cleaned.substring(0, idx));
                String right = normalizeOption(cleaned.substring(idx + delimiter.length()));
                if (StringUtils.hasText(left) && StringUtils.hasText(right) && !left.equalsIgnoreCase(right)) {
                    return List.of(left, right);
                }
            }
        }
        return List.of();
    }

    private String normalizeOption(String raw) {
        return raw
                .replaceAll("(?i)^(should\\s+i\\s+use\\s+|should\\s+we\\s+use\\s+|should\\s+i\\s+|should\\s+we\\s+|choose\\s+|pick\\s+|compare\\s+|between\\s+)", "")
                .replaceAll("(?i)^use\\s+", "")
                .replaceAll("[\"'`]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
