package com.meridian.substrate.app.infrastructure.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import java.util.List;

/**
 * Bearer resolution with the documented SSE carve-out (docs/auth.md §6):
 * the {@code Authorization: Bearer} header everywhere; ON the configured
 * stream paths ONLY (default {@code /api/v1/chat/stream}) a GET may instead
 * carry the token as the RFC 6750 §2.3 {@code access_token} URI query
 * parameter — the standard for browser {@code EventSource}, which cannot set
 * request headers. Chosen over a cookie because it is the RFC-specified
 * fallback, needs no CSRF story, and stays stateless; the deliberate cost
 * (token may reach access logs) is confined to exactly these paths.
 */
final class SseTokenBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final DefaultBearerTokenResolver queryParameterResolver = new DefaultBearerTokenResolver();
    private final List<String> tokenQueryPaths;

    SseTokenBearerTokenResolver(List<String> tokenQueryPaths) {
        this.tokenQueryPaths = List.copyOf(tokenQueryPaths);
        this.queryParameterResolver.setAllowUriQueryParameter(true);
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String headerToken = headerResolver.resolve(request);
        if (headerToken != null) {
            return headerToken;
        }
        if (tokenQueryPaths.contains(request.getRequestURI())) {
            return queryParameterResolver.resolve(request);
        }
        return null;
    }
}
