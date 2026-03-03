package com.debateai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(AppConfig.DebateProperties.class)
public class AppConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService debateExecutor(DebateProperties properties) {
        int poolSize = Math.max(1, properties.execution().threadPoolSize());
        AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("debate-agent-" + threadCounter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
        return Executors.newFixedThreadPool(poolSize, threadFactory);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Validated
    @ConfigurationProperties(prefix = "debate")
    public record DebateProperties(
            @Valid @NotNull Execution execution,
            @Valid @NotNull Retry retry,
            @Valid @NotNull Timeout timeout,
            @Valid @NotNull Llm llm
    ) {

        public record Execution(
                @Min(1) @Max(64) int threadPoolSize
        ) {
        }

        public record Retry(
                @Min(1) @Max(10) int maxAttempts,
                @Min(0) long backoffMillis
        ) {
        }

        public record Timeout(
                @Min(200) long perAgentMillis
        ) {
        }

        public record Llm(
                @NotBlank String provider,
                @Min(1000) @Max(30000) long timeoutMillis,
                @Min(1) @Max(5) int maxAttempts,
                @Valid @NotNull Providers providers,
                @Valid @NotNull Gemini gemini,
                @Valid @NotNull Agents agents
        ) {
            public record Providers(
                    @Valid @NotNull Provider openai,
                    @Valid @NotNull Provider anthropic
            ) {
            }

            public record Provider(
                    String apiKey,
                    @NotBlank String baseUrl,
                    @NotBlank String model
            ) {
            }

            public record Gemini(
                    @NotBlank String model,
                    @Min(5) @Max(120) int timeoutSeconds,
                    @NotBlank String baseUrl
            ) {
            }

            public record Agents(
                    @Valid @NotNull AgentConfig optimist,
                    @Valid @NotNull AgentConfig skeptic,
                    @Valid @NotNull AgentConfig risk,
                    @Valid @NotNull AgentConfig moderator
            ) {
            }

            public record AgentConfig(
                    @NotBlank String provider,
                    @DecimalMin("0.0") @DecimalMax("1.0") double temperature
            ) {
            }
        }
    }
}
