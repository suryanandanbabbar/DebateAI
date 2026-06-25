package com.debateai.client;

import com.debateai.config.AppConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

@Component
public class GroqClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private final WebClient webClient;
    private final AppConfig.DebateProperties.Llm.Provider provider;
    private final String groqApiKey;

    public GroqClient(WebClient.Builder webClientBuilder,
            AppConfig.DebateProperties properties,
            @Value("${GROQ_API_KEY:}") String groqApiKey) {
        this.provider = properties.llm().providers().groq();
        this.webClient = webClientBuilder.build();
        this.groqApiKey = groqApiKey;
        log.info("Groq API key present={} length={}",
                StringUtils.hasText(this.groqApiKey),
                this.groqApiKey == null ? 0 : this.groqApiKey.length());
        log.info("Groq baseUrl={}", provider.baseUrl());
    }

    @Override
    public String provider() {
        return "groq";
    }

    @Override
    public LLMGenerationResponse generate(LLMGenerationRequest request) {
        validateApiKey();

        long start = System.nanoTime();

        Map<String, Object> payload = Map.of(
                "model", request.model(),
                "temperature", request.temperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())));

        LLMGenerationResponse response = webClient.post()
                .uri(provider.baseUrl())
                .headers(headers -> applyCommonHeaders(headers))
                .bodyValue(payload)
                // .retrieve()
                // .bodyToMono(Map.class)
                // .timeout(Duration.ofMillis(request.timeoutMillis()))
                // .retryWhen(retrySpec(request.maxAttempts()))
                // .map(this::parseResponse)
                // .onErrorMap(ex -> new IllegalStateException("OpenAI invocation failed", ex))
                // .block();
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(body -> {
                                    log.error("Groq HTTP error: {}", body);
                                    return new IllegalStateException("Groq error: " + body);
                                }))
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(request.timeoutMillis()))
                .retryWhen(retrySpec(request.maxAttempts()))
                .map(this::parseResponse)
                .block();
        if (response == null) {
            throw new IllegalStateException("Groq invocation returned no response");
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("LLM call completed provider={} model={} durationMs={} usageTokens={}/{}/{}",
                provider(), request.model(), durationMs,
                response.inputTokens(), response.outputTokens(), response.totalTokens());
        return response;
    }

    private void applyCommonHeaders(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + groqApiKey);
    }

    private void validateApiKey() {
        if (!StringUtils.hasText(groqApiKey)) {
            throw new IllegalStateException("GROQ_API_KEY environment variable is not set or empty");
        }
    }

    private Retry retrySpec(int maxAttempts) {
        long retries = Math.max(0, maxAttempts - 1L);
        return Retry.backoff(retries, Duration.ofMillis(250))
                .filter(this::isRetryable);
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private LLMGenerationResponse parseResponse(Map<?, ?> body) {
        if (body == null) {
            throw new IllegalStateException("Groq returned empty response");
        }

        Object choicesObj = body.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("Groq response missing choices");
        }

        Object firstChoiceObj = choices.get(0);
        if (!(firstChoiceObj instanceof Map<?, ?> firstChoice)) {
            throw new IllegalStateException("Groq choice payload is invalid");
        }

        Object messageObj = firstChoice.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            throw new IllegalStateException("Groq response missing message object");
        }

        String content = Objects.toString(message.get("content"), "").trim();
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("Groq response content is blank");
        }

        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;

        Object usageObj = body.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            promptTokens = toInteger(usage.get("prompt_tokens"));
            completionTokens = toInteger(usage.get("completion_tokens"));
            totalTokens = toInteger(usage.get("total_tokens"));
        }

        return new LLMGenerationResponse(content, promptTokens, completionTokens, totalTokens);
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
