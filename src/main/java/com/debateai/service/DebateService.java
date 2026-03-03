package com.debateai.service;

import com.debateai.agent.DebateAgent;
import com.debateai.client.LLMClient;
import com.debateai.client.LLMClientFactory;
import com.debateai.client.LLMExecutionConfig;
import com.debateai.config.AppConfig;
import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateRequest;
import com.debateai.dto.DebateResult;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
    private final AppConfig.DebateProperties properties;

    public DebateService(List<DebateAgent> availableAgents,
                         @Qualifier("debateExecutor") Executor debateExecutor,
                         LLMClientFactory llmClientFactory,
                         AppConfig.DebateProperties properties) {
        this.debateExecutor = debateExecutor;
        this.llmClientFactory = llmClientFactory;
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

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Dispatching {} using provider={} model={} temperature={}",
                        agent.agentName(), resolved.provider(), resolved.executionConfig().model(),
                        String.format(Locale.US, "%.2f", resolved.executionConfig().temperature()));
                return agent.generate(topic, resolved.client(), resolved.executionConfig());
            } catch (RuntimeException ex) {
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

        String provider = agentConfig.provider().trim().toLowerCase(Locale.ROOT);
        LLMClient client = llmClientFactory.getClient(provider);
        String model = resolveModel(provider);

        LLMExecutionConfig executionConfig = new LLMExecutionConfig(
                model,
                agentConfig.temperature(),
                properties.llm().timeoutMillis(),
                properties.llm().maxAttempts()
        );

        return new ResolvedClientConfig(provider, client, executionConfig);
    }

    private String resolveModel(String provider) {
        return switch (provider) {
            case "openai" -> properties.llm().providers().openai().model();
            case "anthropic" -> properties.llm().providers().anthropic().model();
            case "gemini" -> {
                AppConfig.DebateProperties.Llm.Provider gemini = properties.llm().providers().gemini();
                if (gemini == null || !StringUtils.hasText(gemini.model())) {
                    throw new IllegalArgumentException("Gemini provider is selected but debate.llm.providers.gemini.model is missing");
                }
                yield gemini.model();
            }
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
}
