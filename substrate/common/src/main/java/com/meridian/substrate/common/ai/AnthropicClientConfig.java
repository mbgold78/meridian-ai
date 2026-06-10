package com.meridian.substrate.common.ai;

import com.meridian.substrate.common.ai.config.LlmProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.time.Duration;

/**
 * Spring configuration for the Anthropic SDK client bean. Shared by app + ingestion.
 * Active when {@code substrate.llm.provider=anthropic} (the default).
 *
 * <p>Degraded mode: when {@code substrate.llm.anthropic.api-key} is absent or blank,
 * NO SDK client bean is registered and the application boots normally —
 * {@link SdkAnthropicClient} then rejects calls with {@link LlmUnavailableException}.
 */
@Configuration
@ConditionalOnProperty(name = "substrate.llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClientConfig.class);

    @Bean
    @ConditionalOnMissingBean
    @Conditional(AnthropicApiKeyPresent.class)
    public com.anthropic.client.AnthropicClient anthropicSdkClient(LlmProperties properties) {
        LlmProperties.Anthropic config = properties.getAnthropic();

        log.info("Creating Anthropic SDK client bean [model={}, timeout={}ms]",
                config.getModel(), config.getTimeoutMs());

        return AnthropicOkHttpClient.builder()
                .apiKey(config.getApiKey())
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .build();
    }

    /**
     * Matches only when {@code substrate.llm.anthropic.api-key} is present and non-blank.
     * (Plain {@code @ConditionalOnProperty} cannot express "non-empty" — a yml mapping of
     * {@code ${ANTHROPIC_API_KEY:}} resolves to an empty string when the env var is unset.)
     */
    static class AnthropicApiKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String apiKey = context.getEnvironment().getProperty("substrate.llm.anthropic.api-key");
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
