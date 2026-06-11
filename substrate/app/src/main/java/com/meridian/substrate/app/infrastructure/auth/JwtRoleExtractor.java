package com.meridian.substrate.app.infrastructure.auth;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Walks the per-engagement dot-separated roles claim path
 * ({@code substrate.auth.oidc.claims.roles} — e.g. Keycloak's
 * {@code realm_access.roles}, Entra ID's {@code roles}, Okta's
 * {@code groups}); array or single-string leaf. Extracted from
 * {@link OidcIdentitySource} (Stage 1) so the Stage 3 actuator admin gate
 * ({@link OidcSecurityConfig}) consults the IDENTICAL role semantics the
 * persona/admin gates use — one mapping, never two.
 */
final class JwtRoleExtractor {

    private JwtRoleExtractor() {
    }

    static Set<String> extractRoles(Jwt jwt, String rolesClaimPath) {
        if (rolesClaimPath == null || rolesClaimPath.isBlank()) {
            return Set.of();
        }
        Object node = jwt.getClaims();
        for (String segment : rolesClaimPath.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return Set.of();
            }
            node = map.get(segment);
            if (node == null) {
                return Set.of();
            }
        }
        if (node instanceof Collection<?> values) {
            Set<String> roles = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null) {
                    roles.add(value.toString());
                }
            }
            return roles;
        }
        return Set.of(node.toString());
    }
}
