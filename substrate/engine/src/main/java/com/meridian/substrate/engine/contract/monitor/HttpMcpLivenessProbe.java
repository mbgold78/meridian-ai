package com.meridian.substrate.engine.contract.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default {@link McpLivenessProbe}: one JDK-HttpClient GET with short
 * timeouts. Any HTTP status (including 4xx/5xx) proves the endpoint is alive
 * — only connect/timeout/protocol failures count as unreachable.
 *
 * <p><b>SECURITY (ADR D11):</b> outbound HTTP to an operator-registered URI —
 * the same SSRF posture as the existing {@code ProbeService} (port-map §2.2
 * risk register: destination allowlist is tracked pre-production hardening,
 * not silently absorbed here). Deliberately liveness-only so that surface is
 * not enlarged: no body is read, no metrics are fetched.
 */
public class HttpMcpLivenessProbe implements McpLivenessProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpLivenessProbe.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public boolean isReachable(String uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
            return true;                             // any response = alive (liveness only)
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unreachable) {
            log.debug("MCP liveness probe failed [uri={}]: {}", uri, unreachable.getMessage());
            return false;
        }
    }
}
