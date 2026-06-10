package com.meridian.substrate.app.infrastructure.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CORS configuration properties (prefix {@code substrate.cors}).
 *
 * <p>{@code substrate.cors.allowed-origins} accepts either a YAML list or a
 * comma-separated string. The default covers the Vite dev server and the app
 * itself, so a missing key never breaks boot (port-map §2.2: origins are
 * externalized, no longer hardcoded in {@code CorsConfig}).
 */
@Component
@ConfigurationProperties(prefix = "substrate.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:8080");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
