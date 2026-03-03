package com.debateai.client;

import com.debateai.config.AppConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

@Component
public class AnthropicClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private final WebClient webClient;
    private final AppConfig.DebateProperties.Llm.Provider provider;

    public AnthropicClient(WebClient.Builder webClientBuilder, AppConfig.DebateProperties properties) {
        this.provider = properties.llm().providers().anthropic();
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public LLMGenerationResponse generate(LLMGenerationRequest request) {
        if (!StringUtils.hasText(provider.apiKey())) {
            throw new IllegalStateException("Anthropic API key is not configured");
        }

        long start = System.nanoTime();

        Map<String, Object> payload = Map.of(
                "model", request.model(),
                "temperature", request.temperature(),
                "max_tokens", 600,
                "system", request.systemPrompt(),
                "messages", List.of(Map.of("role", "user", "content", request.userPrompt()))
        );

        LLMGenerationResponse response = webClient.post()
                .uri(provider.baseUrl())
                .headers(headers -> applyHeaders(headers, provider.apiKey()))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(request.timeoutMillis()))
                .retryWhen(retrySpec(request.maxAttempts()))
                .map(this::parseResponse)
                .onErrorMap(ex -> new IllegalStateException("Anthropic invocation failed", ex))
                .block();
        if (response == null) {
            throw new IllegalStateException("Anthropic invocation returned no response");
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("LLM call completed provider={} model={} durationMs={} usageTokens={}/{}/{}",
                provider(), request.model(), durationMs,
                response.inputTokens(), response.outputTokens(), response.totalTokens());
        return response;
    }

    private void applyHeaders(HttpHeaders headers, String apiKey) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
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
            throw new IllegalStateException("Anthropic returned empty response");
        }

        Object contentObj = body.get("content");
        if (!(contentObj instanceof List<?> contentList) || contentList.isEmpty()) {
            throw new IllegalStateException("Anthropic response missing content");
        }

        Object firstBlockObj = contentList.get(0);
        if (!(firstBlockObj instanceof Map<?, ?> firstBlock)) {
            throw new IllegalStateException("Anthropic content block is invalid");
        }

        String content = Objects.toString(firstBlock.get("text"), "").trim();
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("Anthropic response content is blank");
        }

        Integer inputTokens = null;
        Integer outputTokens = null;
        Integer totalTokens = null;

        Object usageObj = body.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            inputTokens = toInteger(usage.get("input_tokens"));
            outputTokens = toInteger(usage.get("output_tokens"));
            if (inputTokens != null && outputTokens != null) {
                totalTokens = inputTokens + outputTokens;
            }
        }

        return new LLMGenerationResponse(content, inputTokens, outputTokens, totalTokens);
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
