package com.debateai.client;

import com.debateai.config.AppConfig;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class ProviderSwitcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderSwitcher.class);

    private final ProviderDetector providerDetector;
    private final AppConfig.DebateProperties properties;
    private final String geminiApiKey;

    public ProviderSwitcher(ProviderDetector providerDetector,
                            AppConfig.DebateProperties properties,
                            @Value("${GEMINI_API_KEY:${GOOGLE_API_KEY:}}") String geminiApiKey) {
        this.providerDetector = providerDetector;
        this.properties = properties;
        this.geminiApiKey = geminiApiKey;
    }

    public String resolveProviderByKeyFormat(String configuredProvider) {
        String normalizedConfigured = normalize(configuredProvider);
        String configuredKey = keyForProvider(normalizedConfigured);
        if (!StringUtils.hasText(configuredKey)) {
            return normalizedConfigured;
        }

        try {
            String detected = providerDetector.detectProviderFromKey(configuredKey);
            if (!detected.equals(normalizedConfigured)) {
                log.warn("Configured provider was {} but found key for {}; switching.",
                        normalizedConfigured, detected);
                return detected;
            }
            return normalizedConfigured;
        } catch (IllegalArgumentException ex) {
            return normalizedConfigured;
        }
    }

    public String chooseProviderForKey(String apiKey, String topic) {
        String detected = providerDetector.detectProviderFromKey(apiKey);
        log.info("Selected provider {} for topic '{}'", detected, summarizeTopic(topic));
        return detected;
    }

    public String findAlternativeProvider(String currentProvider) {
        String normalizedCurrent = normalize(currentProvider);
        List<String> providers = List.of("openai", "anthropic", "gemini");

        for (String candidate : providers) {
            if (candidate.equals(normalizedCurrent)) {
                continue;
            }
            String key = keyForProvider(candidate);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            try {
                String detected = providerDetector.detectProviderFromKey(key);
                if (detected.equals(candidate)) {
                    return candidate;
                }
            } catch (IllegalArgumentException ignored) {
                // skip invalid formats
            }
        }

        return normalizedCurrent;
    }

    public boolean isApiKeyMismatchError(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof WebClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 401 || status == 403) {
                    return true;
                }
            }
            String message = cursor.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("invalid api key")
                        || normalized.contains("api key")
                        || normalized.contains("authentication")
                        || normalized.contains("unauthorized")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String keyForProvider(String provider) {
        return switch (provider) {
            case "openai" -> properties.llm().providers().openai().apiKey();
            case "anthropic" -> properties.llm().providers().anthropic().apiKey();
            case "gemini" -> geminiApiKey;
            default -> "";
        };
    }

    private String normalize(String provider) {
        if (!StringUtils.hasText(provider)) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String summarizeTopic(String topic) {
        if (!StringUtils.hasText(topic)) {
            return "unknown-topic";
        }
        String trimmed = topic.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "...";
    }
}
