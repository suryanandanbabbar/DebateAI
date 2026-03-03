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
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClient;

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
    public RestClient llmRestClient(DebateProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        if (StringUtils.hasText(properties.llm().baseUrl())) {
            builder.baseUrl(properties.llm().baseUrl());
        }
        return builder.build();
    }

    @Validated
    @ConfigurationProperties(prefix = "debate")
    public record DebateProperties(
            @Valid @NotNull Execution execution,
            @Valid @NotNull Retry retry,
            @Valid @NotNull Timeout timeout,
            @Valid @NotNull Llm llm,
            @Valid @NotNull Prompts prompts
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
                boolean mockMode,
                String baseUrl,
                @NotBlank String chatPath,
                @NotBlank String model,
                @DecimalMin("0.0") @DecimalMax("1.0") double temperature,
                String apiKey
        ) {
        }

        public record Prompts(
                @NotBlank String optimist,
                @NotBlank String skeptic,
                @NotBlank String riskAnalyst,
                @NotBlank String moderator
        ) {
        }
    }
}
