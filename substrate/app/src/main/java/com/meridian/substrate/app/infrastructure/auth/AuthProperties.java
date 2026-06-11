package com.meridian.substrate.app.infrastructure.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code substrate.auth.*} — the Phase 5 auth-mode ladder (production plan §4;
 * port-map S9; docs/auth.md).
 *
 * <ul>
 *   <li>{@code anonymous} (default) — Phase 1–4 behavior byte-for-byte:
 *       NO Spring Security on the request path (see
 *       {@link AuthModeEnvironmentPostProcessor}), persona headers trusted,
 *       MCP {@code X-MCP-Principal} header trusted.</li>
 *   <li>{@code oidc} — generic OIDC resource server: ANY conformant IdP via
 *       {@code issuer-uri} (customer brings Okta / Entra ID / Auth0 / internal;
 *       Keycloak is the throwaway test IdP). Persona and MCP header trust are
 *       severed; identity comes from the verified JWT.</li>
 * </ul>
 *
 * <p>All claim mappings are per-engagement configuration — no IdP is
 * hard-coded anywhere (plan §4 amendment).
 */
@Component
@ConfigurationProperties(prefix = "substrate.auth")
public class AuthProperties {

    public static final String MODE_ANONYMOUS = "anonymous";
    public static final String MODE_OIDC = "oidc";

    /** {@code substrate.auth.mode} — {@code anonymous} (default) | {@code oidc}. */
    private String mode = MODE_ANONYMOUS;

    private Oidc oidc = new Oidc();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        if (mode == null || mode.isBlank()) {
            this.mode = MODE_ANONYMOUS;
            return;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_ANONYMOUS.equals(normalized) && !MODE_OIDC.equals(normalized)) {
            throw new IllegalArgumentException("substrate.auth.mode must be '"
                    + MODE_ANONYMOUS + "' or '" + MODE_OIDC + "' (got '" + mode + "')");
        }
        this.mode = normalized;
    }

    public boolean isOidc() {
        return MODE_OIDC.equals(mode);
    }

    public Oidc getOidc() {
        return oidc;
    }

    public void setOidc(Oidc oidc) {
        this.oidc = oidc;
    }

    /** {@code substrate.auth.oidc.*} — only read when {@code mode=oidc}. */
    public static class Oidc {

        /**
         * OIDC issuer ({@code https://idp.example.com/realms/x}). Token
         * signing keys are discovered from
         * {@code <issuer>/.well-known/openid-configuration}; the {@code iss}
         * claim is validated against this value.
         */
        private String issuerUri;

        /**
         * Direct JWK Set URI fallback for IdPs whose discovery document is not
         * reachable from the app (air-gapped customer networks). When set, it
         * wins over issuer discovery; issuer validation still applies when
         * {@code issuer-uri} is also configured.
         */
        private String jwkSetUri;

        /**
         * Optional expected audience. When set, tokens whose {@code aud} claim
         * does not contain this value are rejected. Per-engagement.
         */
        private String audience;

        private Claims claims = new Claims();
        private Persona persona = new Persona();

        /**
         * Roles (post claim-mapping) that satisfy admin gates
         * (BrandingAdminAuthorizer & friends). Per-engagement; the Keycloak
         * dev realm ships {@code substrate-admin}.
         */
        private List<String> adminRoles = new ArrayList<>(List.of("substrate-admin"));

        private Mcp mcp = new Mcp();
        private Sse sse = new Sse();

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public Claims getClaims() {
            return claims;
        }

        public void setClaims(Claims claims) {
            this.claims = claims;
        }

        public Persona getPersona() {
            return persona;
        }

        public void setPersona(Persona persona) {
            this.persona = persona;
        }

        public List<String> getAdminRoles() {
            return adminRoles;
        }

        public void setAdminRoles(List<String> adminRoles) {
            this.adminRoles = adminRoles;
        }

        public Mcp getMcp() {
            return mcp;
        }

        public void setMcp(Mcp mcp) {
            this.mcp = mcp;
        }

        public Sse getSse() {
            return sse;
        }

        public void setSse(Sse sse) {
            this.sse = sse;
        }
    }

    /**
     * Claim mappings — configurable per engagement because every IdP shapes
     * its tokens differently (Keycloak: {@code realm_access.roles}; Entra ID:
     * {@code roles}; Okta: {@code groups} or a custom claim).
     */
    public static class Claims {

        /**
         * Dot-separated path to the roles claim inside the access token
         * (e.g. {@code roles}, {@code realm_access.roles},
         * {@code resource_access.substrate-app.roles}). The leaf may be a JSON
         * array or a single string.
         */
        private String roles = "roles";

        /**
         * Claim carrying the human display label. Falls back to
         * {@code preferred_username}, then {@code sub}.
         */
        private String displayName = "name";

        public String getRoles() {
            return roles;
        }

        public void setRoles(String roles) {
            this.roles = roles;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * Role → persona-level projection: PersonaContext stays the app-internal
     * vocabulary ({@code L3_ANALYST}, {@code EXECUTIVE}, {@code ADMIN}, …);
     * under oidc the level is DERIVED from verified roles instead of the
     * spoofable {@code X-Persona-Level} header (S9 severed).
     */
    public static class Persona {

        /**
         * Ordered role → persona-level map (YAML insertion order preserved);
         * the FIRST entry whose role the caller holds wins.
         */
        private Map<String, String> roleMap = new LinkedHashMap<>();

        /** Level assigned when no mapped role matches. */
        private String defaultLevel = "L3_ANALYST";

        public Map<String, String> getRoleMap() {
            return roleMap;
        }

        public void setRoleMap(Map<String, String> roleMap) {
            this.roleMap = roleMap;
        }

        public String getDefaultLevel() {
            return defaultLevel;
        }

        public void setDefaultLevel(String defaultLevel) {
            this.defaultLevel = defaultLevel;
        }
    }

    /**
     * MCP principal binding (ADR-002 D8 / agents.md §8): under oidc the
     * {@code X-MCP-Principal} header is DEAD; the principal carried to tool
     * handlers is a claim of the verified client-credential JWT, matched
     * against {@code agent_registrations.mcp_principal_id} /
     * {@code contract_parties.mcp_principal_id}.
     */
    public static class Mcp {

        /**
         * Claim used as the MCP principal id. Default {@code sub}
         * (Keycloak client-credentials: the service-account user id, a stable
         * UUID). Engagements that register the OAuth client id instead set
         * this to {@code azp}. Whatever the setting, a missing claim falls
         * back to {@code azp} before yielding no principal.
         */
        private String principalClaim = "sub";

        public String getPrincipalClaim() {
            return principalClaim;
        }

        public void setPrincipalClaim(String principalClaim) {
            this.principalClaim = principalClaim;
        }
    }

    /**
     * SSE token transport: browser {@code EventSource} cannot set an
     * {@code Authorization} header, so the listed GET endpoints additionally
     * accept the RFC 6750 §2.3 {@code access_token} URI query parameter —
     * the standard bearer-token fallback for header-less clients. Confined to
     * exactly these paths so tokens never ride URLs anywhere else.
     */
    public static class Sse {

        /** Exact request paths where {@code ?access_token=} is accepted. */
        private List<String> tokenQueryPaths = new ArrayList<>(List.of("/api/v1/chat/stream"));

        public List<String> getTokenQueryPaths() {
            return tokenQueryPaths;
        }

        public void setTokenQueryPaths(List<String> tokenQueryPaths) {
            this.tokenQueryPaths = tokenQueryPaths;
        }
    }
}
