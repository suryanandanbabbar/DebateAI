package com.debateai.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

@Component
public class GeminiClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final WebClient webClient;
    private final String configuredModel;
    private final String geminiApiKey;
    private final long providerTimeoutMillis;

    public GeminiClient(WebClient.Builder webClientBuilder,
                        @Value("${debate.llm.providers.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
                        String baseUrl,
                        @Value("${debate.llm.providers.gemini.model:gemini-2.5-flash}") String configuredModel,
                        @Value("${debate.llm.providers.gemini.timeout-millis:0}") long providerTimeoutMillis,
                        @Value("${GEMINI_API_KEY:${GOOGLE_API_KEY:}}") String geminiApiKey) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.configuredModel = configuredModel;
        this.providerTimeoutMillis = providerTimeoutMillis;
        this.geminiApiKey = geminiApiKey;
        log.info("Gemini API key present={} length={}",
                StringUtils.hasText(this.geminiApiKey),
                this.geminiApiKey == null ? 0 : this.geminiApiKey.length());
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public LLMGenerationResponse generate(LLMGenerationRequest request) {
        validateApiKey();

        String model = StringUtils.hasText(request.model()) ? request.model() : configuredModel;
        long timeoutMillis = resolveTimeoutMillis(request.timeoutMillis());
        long start = System.nanoTime();

        Map<String, Object> body = toGeminiRequestBody(request);

        LLMGenerationResponse response = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .queryParam("key", geminiApiKey)
                        .build(model))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMillis))
                .retryWhen(retrySpec(request.maxAttempts()))
                .map(this::extractResponse)
                .onErrorMap(ex -> new IllegalStateException("Gemini invocation failed", ex))
                .block();

        if (response == null) {
            throw new IllegalStateException("Gemini invocation returned no response");
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("LLM call completed provider={} model={} durationMs={} usageTokens={}/{}/{}",
                provider(), model, durationMs,
                response.inputTokens(), response.outputTokens(), response.totalTokens());
        return response;
    }

    private Map<String, Object> toGeminiRequestBody(LLMGenerationRequest request) {
        String mergedPrompt = "System Role:\n" + request.systemPrompt()
                + "\n\nUser:\n" + request.userPrompt();

        return Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", mergedPrompt)))
                )
        );
    }

    private LLMGenerationResponse extractResponse(Map<?, ?> body) {
        if (body == null) {
            throw new IllegalStateException("Gemini returned empty response");
        }

        String text = extractText(body);
        Integer inputTokens = null;
        Integer outputTokens = null;
        Integer totalTokens = null;

        Object usageObj = body.get("usageMetadata");
        if (usageObj instanceof Map<?, ?> usage) {
            inputTokens = toInteger(usage.get("promptTokenCount"));
            outputTokens = toInteger(usage.get("candidatesTokenCount"));
            totalTokens = toInteger(usage.get("totalTokenCount"));
        }

        return new LLMGenerationResponse(text, inputTokens, outputTokens, totalTokens);
    }

    private String extractText(Map<?, ?> body) {
        Object candidatesObj = body.get("candidates");
        if (!(candidatesObj instanceof List<?> candidates) || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini response missing candidates");
        }

        Object firstCandidateObj = candidates.get(0);
        if (!(firstCandidateObj instanceof Map<?, ?> firstCandidate)) {
            throw new IllegalStateException("Gemini candidate payload is invalid");
        }

        Object contentObj = firstCandidate.get("content");
        if (!(contentObj instanceof Map<?, ?> content)) {
            throw new IllegalStateException("Gemini response missing content");
        }

        Object partsObj = content.get("parts");
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty()) {
            throw new IllegalStateException("Gemini response missing content.parts");
        }

        Object firstPartObj = parts.get(0);
        if (!(firstPartObj instanceof Map<?, ?> firstPart)) {
            throw new IllegalStateException("Gemini content part is invalid");
        }

        String text = Objects.toString(firstPart.get("text"), "").trim();
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("Gemini response text is blank");
        }
        return text;
    }

    private long resolveTimeoutMillis(long requestTimeoutMillis) {
        return providerTimeoutMillis > 0L ? providerTimeoutMillis : requestTimeoutMillis;
    }

    private Retry retrySpec(int maxAttempts) {
        long retries = Math.max(0, maxAttempts - 1L);
        return Retry.backoff(retries, Duration.ofMillis(300))
                .filter(this::isRetryable);
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
        }
        return true;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private void validateApiKey() {
        if (!StringUtils.hasText(geminiApiKey)) {
            throw new IllegalStateException(
                    "Gemini API key is missing. Set GEMINI_API_KEY or GOOGLE_API_KEY environment variable.");
        }
    }
}
