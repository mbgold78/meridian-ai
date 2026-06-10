package com.meridian.substrate.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for beans that Spring Boot's autoconfiguration doesn't provide in this
 * CLI-only module (no web starter present).
 *
 * <p>SdkAnthropicClient + LlmProperties + AnthropicClientConfig live in common/
 * and are picked up by the root-level component scan — no explicit wiring needed.
 */
@Configuration
public class IngestionConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
