package com.meridian.substrate.app.infrastructure.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code substrate.auth.mode=anonymous} (default): the pre-Phase-5 persona
 * header trust, UNCHANGED semantics (port-map S9, accepted posture for
 * Phase 1–4 and local dev). Headers are spoofable by design here; the entire
 * existing suite pins this behavior byte-for-byte.
 *
 * <p>{@code personaLevel} is the RAW header value — {@code null} when the
 * header is absent so the projection leaves the {@code PersonaContext}
 * default ({@code L3_ANALYST}) untouched, exactly as the old interceptor did.
 * Roles are always empty: anonymous mode has no real roles, and admin gates
 * keep their documented persona-level check.
 *
 * <p><b>SSE carve-out (the {@code SseTokenBearerTokenResolver} mirror):</b>
 * browser {@code EventSource} cannot set request headers, so ON the
 * configured stream paths ONLY (the same
 * {@code substrate.auth.oidc.sse.token-query-paths} list, default
 * {@code /api/v1/chat/stream}) an absent {@code X-Persona-Level} header
 * falls back to the {@code personaLevel} URI query parameter. Without this,
 * the frontend's documented streaming transport ("anonymous mode: persona as
 * a query param") was a dead parameter — every anonymous SSE stream silently
 * ran as the {@code L3_ANALYST} default regardless of the user's persona. A
 * query param is no weaker than the already-spoofable header in this mode.
 */
@Component
@ConditionalOnProperty(name = "substrate.auth.mode",
        havingValue = AuthProperties.MODE_ANONYMOUS, matchIfMissing = true)
public class AnonymousIdentitySource implements IdentitySource {

    private final AuthProperties authProperties;

    public AnonymousIdentitySource(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public SubstrateIdentity resolve(HttpServletRequest request) {
        String personaLevel = request.getHeader("X-Persona-Level");
        if (personaLevel == null && isStreamPath(request)) {
            personaLevel = request.getParameter("personaLevel");
        }
        return new SubstrateIdentity(
                request.getHeader("X-Persona-Id"),
                request.getHeader("X-Persona-Label"),
                java.util.Set.of(),
                personaLevel,
                SubstrateIdentity.Source.ANONYMOUS);
    }

    private boolean isStreamPath(HttpServletRequest request) {
        return authProperties.getOidc().getSse().getTokenQueryPaths()
                .contains(request.getRequestURI());
    }
}
