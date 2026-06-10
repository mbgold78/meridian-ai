package com.meridian.substrate.app.api.v1.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Controller providing health check and application info endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String applicationName;

    public HealthController(
            @Value("${spring.application.name:meridian-substrate}") String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * Health check endpoint.
     *
     * @return a map containing the application status and current timestamp
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        );
    }

    /**
     * Application info endpoint.
     *
     * @return a map containing the application name and version
     */
    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of(
                "name", applicationName,
                "version", "0.1.0"
        );
    }
}
