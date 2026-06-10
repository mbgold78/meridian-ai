package com.meridian.substrate.app.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two CORS hardening fixes from the port (port-map §2.2):
 * PATCH is an allowed method, and origins come from
 * {@code substrate.cors.allowed-origins} (with safe localhost defaults)
 * instead of being hardcoded.
 */
class CorsConfigTest {

    /** Exposes the protected registrations map for assertions. */
    static class CapturingCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }

    private CorsConfiguration applyMappings(CorsProperties properties) {
        WebMvcConfigurer configurer = new CorsConfig().corsConfigurer(properties);
        CapturingCorsRegistry registry = new CapturingCorsRegistry();
        configurer.addCorsMappings(registry);
        return registry.configurations().get("/api/**");
    }

    @Test
    void defaultOrigins_andPatchAllowed() {
        CorsConfiguration config = applyMappings(new CorsProperties());

        assertThat(config).as("a CORS mapping must be registered for /api/**").isNotNull();
        assertThat(config.getAllowedMethods())
                .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(config.getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://localhost:8080");
        assertThat(config.getAllowedHeaders()).contains("*");
        assertThat(config.getAllowCredentials()).isTrue();
    }

    @Test
    void configuredOrigins_replaceDefaults() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://app.example.com"));

        CorsConfiguration config = applyMappings(properties);

        assertThat(config.getAllowedOrigins()).containsExactly("https://app.example.com");
    }
}
