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
 */
@Component
@ConditionalOnProperty(name = "substrate.auth.mode",
        havingValue = AuthProperties.MODE_ANONYMOUS, matchIfMissing = true)
public class AnonymousIdentitySource implements IdentitySource {

    @Override
    public SubstrateIdentity resolve(HttpServletRequest request) {
        return new SubstrateIdentity(
                request.getHeader("X-Persona-Id"),
                request.getHeader("X-Persona-Label"),
                java.util.Set.of(),
                request.getHeader("X-Persona-Level"),
                SubstrateIdentity.Source.ANONYMOUS);
    }
}
