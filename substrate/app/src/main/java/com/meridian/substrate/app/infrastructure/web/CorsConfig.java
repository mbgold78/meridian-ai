package com.meridian.substrate.app.infrastructure.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration allowing the Vite dev server (and any configured origin)
 * to call the API.
 *
 * <p>Port hardening (port-map §2.2): {@code PATCH} added to the allowed methods
 * (homepoc omitted it while exposing PATCH endpoints — capability rename/reparent/
 * sequence, branding update, process steps), and origins are externalized to
 * {@code substrate.cors.allowed-origins} instead of being hardcoded.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(CorsProperties corsProperties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
