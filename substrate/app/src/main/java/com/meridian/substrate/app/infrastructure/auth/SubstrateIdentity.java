package com.meridian.substrate.app.infrastructure.auth;

import java.util.Set;

/**
 * The Phase 5 identity abstraction (port-map S9): one value object describing
 * WHO is calling, produced per-request by exactly one {@link IdentitySource}
 * and projected onto the legacy {@link
 * com.meridian.substrate.app.infrastructure.web.PersonaContext} at the
 * interceptor layer — PersonaContext consumers (controllers, actor mappers,
 * envelope meta) are deliberately untouched.
 *
 * @param principalId  stable principal id. Anonymous mode: the verbatim
 *                     {@code X-Persona-Id} header (nullable, spoofable —
 *                     accepted pre-Phase-5 posture). OIDC mode: the verified
 *                     token {@code sub} — the IAM-compatible stable user id
 *                     that actor fields and hash-chain entries record
 *                     (ADR-001 D11; docs/auth.md §actor identity).
 * @param displayLabel human-readable label for {@code actor_label} columns.
 *                     Anonymous: {@code X-Persona-Label}. OIDC: the configured
 *                     display-name claim ({@code name} →
 *                     {@code preferred_username} → {@code sub} fallback).
 * @param roles        verified roles after claim mapping. Always empty in
 *                     anonymous mode (there ARE no real roles to consult —
 *                     admin gates keep the documented persona-level check
 *                     there).
 * @param personaLevel persona level for the app's existing vocabulary.
 *                     Anonymous: the raw {@code X-Persona-Level} header value
 *                     or {@code null} (= keep the PersonaContext default,
 *                     preserving pre-Phase-5 semantics exactly). OIDC: derived
 *                     from roles via {@code substrate.auth.oidc.persona.*}.
 * @param source       which ladder rung produced this identity.
 */
public record SubstrateIdentity(
        String principalId,
        String displayLabel,
        Set<String> roles,
        String personaLevel,
        Source source) {

    public SubstrateIdentity {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** An unauthenticated caller on a permitted path under the given mode. */
    public static SubstrateIdentity unauthenticated(Source source) {
        return new SubstrateIdentity(null, null, Set.of(), null, source);
    }

    public boolean hasAnyRole(Iterable<String> candidates) {
        for (String candidate : candidates) {
            if (roles.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public enum Source { ANONYMOUS, OIDC }
}
