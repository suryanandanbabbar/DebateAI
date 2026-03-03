package com.debateai.client;

import com.debateai.config.AppConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class GeminiClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final WebClient webClient;
    private final AppConfig.DebateProperties.Llm.Gemini geminiConfig;
    private final String geminiApiKey;

    public GeminiClient(WebClient.Builder webClientBuilder,
                        AppConfig.DebateProperties properties,
                        @Value("${GEMINI_API_KEY:${GOOGLE_API_KEY:}}") String geminiApiKey) {
        this.geminiConfig = properties.llm().gemini();
        this.webClient = webClientBuilder.baseUrl(geminiConfig.baseUrl()).build();
        this.geminiApiKey = geminiApiKey;
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public LLMGenerationResponse generate(LLMGenerationRequest request) {
        validateApiKey();

        long start = System.nanoTime();

        Map<String, Object> requestBody = toGeminiRequestBody(request);

        LLMGenerationResponse response = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .queryParam("key", geminiApiKey)
                        .build(request.model()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapGeminiError)
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(request.timeoutMillis()))
                .retryWhen(retrySpec(request.maxAttempts()))
                .map(this::extractResponse)
                .onErrorMap(WebClientResponseException.class,
                        ex -> new RuntimeException("Gemini request failed with status " + ex.getStatusCode().value()))
                .onErrorMap(ex -> ex instanceof RuntimeException ? ex : new RuntimeException("Gemini request failed"))
                .block();

        if (response == null) {
            throw new RuntimeException("Gemini request failed: empty response");
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("LLM call completed provider={} model={} durationMs={} usageTokens={}/{}/{}",
                provider(), request.model(), durationMs,
                response.inputTokens(), response.outputTokens(), response.totalTokens());
        return response;
    }

    private Mono<RuntimeException> mapGeminiError(org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(ignored -> new RuntimeException("Gemini API returned an error (status=" + status + ")"));
    }

    private Map<String, Object> toGeminiRequestBody(LLMGenerationRequest request) {
        String prompt = mergePrompt(request.systemPrompt(), request.userPrompt());
        return Map.of(
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                )
        );
    }

    private String mergePrompt(String systemPrompt, String userPrompt) {
        if (!StringUtils.hasText(systemPrompt)) {
            return userPrompt;
        }
        return systemPrompt + "\n\n" + userPrompt;
    }

    private LLMGenerationResponse extractResponse(Map<?, ?> body) {
        if (body == null) {
            throw new RuntimeException("Gemini response parsing failed: empty payload");
        }

        String text = extractGeneratedText(body);
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

    private String extractGeneratedText(Map<?, ?> body) {
        Object candidatesObj = body.get("candidates");
        if (!(candidatesObj instanceof List<?> candidates) || candidates.isEmpty()) {
            throw new RuntimeException("Gemini response parsing failed: missing candidates");
        }

        Object firstCandidateObj = candidates.get(0);
        if (!(firstCandidateObj instanceof Map<?, ?> firstCandidate)) {
            throw new RuntimeException("Gemini response parsing failed: invalid candidate format");
        }

        Object contentObj = firstCandidate.get("content");
        if (!(contentObj instanceof Map<?, ?> content)) {
            throw new RuntimeException("Gemini response parsing failed: missing content");
        }

        Object partsObj = content.get("parts");
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty()) {
            throw new RuntimeException("Gemini response parsing failed: missing parts");
        }

        Object firstPartObj = parts.get(0);
        if (!(firstPartObj instanceof Map<?, ?> firstPart)) {
            throw new RuntimeException("Gemini response parsing failed: invalid part format");
        }

        String text = Objects.toString(firstPart.get("text"), "").trim();
        if (!StringUtils.hasText(text)) {
            throw new RuntimeException("Gemini response parsing failed: text is blank");
        }
        return text;
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
        return !(throwable instanceof IllegalStateException);
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
                    "Gemini API key is missing. Set GEMINI_API_KEY (or GOOGLE_API_KEY) in the environment.");
        }
    }
}
