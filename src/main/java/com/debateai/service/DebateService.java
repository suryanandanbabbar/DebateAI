package com.debateai.service;

import com.debateai.agent.DebateAgent;
import com.debateai.config.AppConfig;
import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateRequest;
import com.debateai.dto.DebateResult;
import java.util.List;
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
    private final AppConfig.DebateProperties properties;

    public DebateService(List<DebateAgent> availableAgents,
                         @Qualifier("debateExecutor") Executor debateExecutor,
                         AppConfig.DebateProperties properties) {
        this.debateExecutor = debateExecutor;
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

        DebateResult result = moderatorAgent.moderate(topic, responses);
        log.info("Debate completed with confidence score {}", result.confidenceScore());
        return result;
    }

    private CompletableFuture<AgentResponse> executeAgent(DebateAgent agent, String topic) {
        return CompletableFuture.supplyAsync(() -> executeWithRetry(agent, topic), debateExecutor)
                .completeOnTimeout(timeoutResponse(agent), properties.timeout().perAgentMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> failedResponse(agent, unwrap(throwable)));
    }

    private AgentResponse executeWithRetry(DebateAgent agent, String topic) {
        int maxAttempts = properties.retry().maxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return agent.generate(topic);
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
}
