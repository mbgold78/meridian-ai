package com.meridian.substrate.app.infrastructure.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * {@code substrate.auth.mode=oidc}: identity from the VERIFIED JWT placed in
 * the SecurityContext by the resource-server filter chain
 * ({@link OidcSecurityConfig}) — severs port-map seam S9.
 *
 * <ul>
 *   <li>{@code principalId} = token {@code sub} — the IAM-compatible stable
 *       user id recorded by every actor field / hash-chain entry
 *       (ADR-001 D11; docs/auth.md).</li>
 *   <li>{@code roles} = configurable claim path
 *       ({@code substrate.auth.oidc.claims.roles}, dot-separated — e.g.
 *       Keycloak's {@code realm_access.roles}); array or single-string leaf.</li>
 *   <li>{@code personaLevel} = first match in the ordered
 *       {@code substrate.auth.oidc.persona.role-map}, else the configured
 *       default — persona is now DERIVED, never client-asserted.</li>
 *   <li>{@code displayLabel} = display-name claim →
 *       {@code preferred_username} → {@code sub}.</li>
 * </ul>
 *
 * <p><b>Header-spoof death (S9):</b> any {@code X-Persona-*} header arriving
 * in oidc mode is IGNORED and logged at WARN — it influences nothing.
 */
@Component
@ConditionalOnProperty(name = "substrate.auth.mode", havingValue = AuthProperties.MODE_OIDC)
public class OidcIdentitySource implements IdentitySource {

    private static final Logger log = LoggerFactory.getLogger(OidcIdentitySource.class);

    private static final String[] PERSONA_HEADERS =
            {"X-Persona-Level", "X-Persona-Id", "X-Persona-Label"};

    private final AuthProperties authProperties;

    public OidcIdentitySource(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public SubstrateIdentity resolve(HttpServletRequest request) {
        warnIfPersonaHeadersPresent(request);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            // Unauthenticated caller on a permitAll path (e.g. /api/v1/health):
            // no principal, default persona level applies downstream.
            return SubstrateIdentity.unauthenticated(SubstrateIdentity.Source.OIDC);
        }

        Jwt jwt = jwtAuthentication.getToken();
        Set<String> roles = extractRoles(jwt);
        return new SubstrateIdentity(
                jwt.getSubject(),
                displayLabel(jwt),
                roles,
                personaLevel(roles),
                SubstrateIdentity.Source.OIDC);
    }

    private void warnIfPersonaHeadersPresent(HttpServletRequest request) {
        for (String header : PERSONA_HEADERS) {
            if (request.getHeader(header) != null) {
                log.warn("Ignoring spoofable {} header under substrate.auth.mode=oidc — "
                                + "identity comes from the verified token only (port-map S9 severed)"
                                + " [path={}]",
                        header, request.getRequestURI());
            }
        }
    }

    /** Walks the dot-separated roles claim path; array or single-string leaf. */
    private Set<String> extractRoles(Jwt jwt) {
        // Shared with the Stage 3 actuator admin gate (OidcSecurityConfig) —
        // one role mapping, never two.
        return JwtRoleExtractor.extractRoles(jwt, authProperties.getOidc().getClaims().getRoles());
    }

    /** Ordered first-match role → persona-level projection. */
    private String personaLevel(Set<String> roles) {
        AuthProperties.Persona persona = authProperties.getOidc().getPersona();
        for (Map.Entry<String, String> mapping : persona.getRoleMap().entrySet()) {
            if (roles.contains(mapping.getKey())) {
                return mapping.getValue();
            }
        }
        return persona.getDefaultLevel();
    }

    private String displayLabel(Jwt jwt) {
        String claim = authProperties.getOidc().getClaims().getDisplayName();
        String label = claim == null || claim.isBlank() ? null : jwt.getClaimAsString(claim);
        if (label == null || label.isBlank()) {
            label = jwt.getClaimAsString("preferred_username");
        }
        if (label == null || label.isBlank()) {
            label = jwt.getSubject();
        }
        return label;
    }
}
