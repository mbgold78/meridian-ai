package com.meridian.substrate.app.infrastructure.auth;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code substrate.auth.mode=oidc} resource-server wiring (production plan
 * §4; port-map S9; docs/auth.md). GENERIC by construction: nothing here knows
 * which IdP issued the token — issuer/JWKS/audience/claims are all
 * per-engagement {@link AuthProperties} configuration; Keycloak is merely the
 * throwaway test IdP the compose stack and the Testcontainers IT point at.
 *
 * <p><b>Surface posture:</b> {@code /api/**} and the {@code /mcp} servlet
 * require a verified bearer token (unauthenticated → 401); the SPA static
 * shell, springdoc, the actuator health/info probes and {@code /api/v1/health}
 * stay public. Authorization beyond authentication is NOT done here — role
 * decisions live at the existing gates ({@code BrandingAdminAuthorizer}
 * consults {@link SubstrateIdentityContext} roles), keeping 403 semantics
 * (and the never-error-colored decline/pause vocabulary) untouched — with ONE
 * Stage 3 exception: actuator endpoints beyond health/info (metrics et al.,
 * exposure computed by {@code ObservabilityEnvironmentPostProcessor}) are
 * ops/admin surface, not product surface, so they are gated HERE on the
 * engagement's {@code substrate.auth.oidc.admin-roles} via the same
 * {@link JwtRoleExtractor} mapping the product admin gates use.
 *
 * <p><b>SSE:</b> browser {@code EventSource} cannot set headers, so the
 * configured stream paths additionally accept the RFC 6750 §2.3
 * {@code access_token} query parameter (see {@link SseTokenBearerTokenResolver}).
 *
 * <p>In anonymous mode this class is fully inert: the condition is off AND
 * {@link AuthModeEnvironmentPostProcessor} excludes security
 * auto-configuration entirely.
 */
@Configuration
@ConditionalOnProperty(name = "substrate.auth.mode", havingValue = AuthProperties.MODE_OIDC)
public class OidcSecurityConfig {

    @Bean
    public SecurityFilterChain substrateOidcSecurityFilterChain(HttpSecurity http,
                                                                JwtDecoder jwtDecoder,
                                                                BearerTokenResolver bearerTokenResolver,
                                                                AuthProperties authProperties)
            throws Exception {
        http
                // Stateless bearer-token API: no sessions, no CSRF surface.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Delegates to the existing MVC CORS mappings (CorsConfig), so
                // preflights pass before authorization.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        // Ops/probe surface stays public (same as anonymous mode):
                        // ALB/compose health checks hit /actuator/health unauthenticated.
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class,
                                InfoEndpoint.class)).permitAll()
                        // Stage 3: every other actuator endpoint (metrics et al.)
                        // requires a verified token carrying an engagement admin role.
                        .requestMatchers(EndpointRequest.toAnyEndpoint())
                        .access(actuatorAdminOnly(authProperties))
                        // The data plane and the MCP servlet require a verified token.
                        .requestMatchers("/api/**", "/mcp", "/mcp/**").authenticated()
                        // SPA shell, hashed assets, springdoc: public.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    /**
     * Stage 3 actuator admin gate: ALLOW iff the verified JWT carries one of
     * the per-engagement {@code substrate.auth.oidc.admin-roles} under the
     * configured roles claim path — the SAME mapping
     * {@code OidcIdentitySource} / {@code BrandingAdminAuthorizer} consult.
     * Unauthenticated callers are denied (→ 401 via the bearer-token entry
     * point); authenticated non-admins get 403.
     */
    private static AuthorizationManager<RequestAuthorizationContext> actuatorAdminOnly(
            AuthProperties authProperties) {
        return (authentication, context) -> {
            if (!(authentication.get() instanceof JwtAuthenticationToken jwtAuthentication)
                    || !jwtAuthentication.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            var roles = JwtRoleExtractor.extractRoles(jwtAuthentication.getToken(),
                    authProperties.getOidc().getClaims().getRoles());
            boolean admin = authProperties.getOidc().getAdminRoles().stream()
                    .anyMatch(roles::contains);
            return new AuthorizationDecision(admin);
        };
    }

    /**
     * Generic decoder: JWKS keys from {@code jwk-set-uri} when configured
     * (air-gapped fallback), else issuer discovery; {@code iss} validated
     * whenever an issuer is configured; optional {@code aud} containment
     * validation. Tests replace this bean with a mock decoder
     * ({@code @MockitoBean}) — the JWT-mock lane on the Docker-less dev box.
     */
    @Bean
    public JwtDecoder substrateJwtDecoder(AuthProperties authProperties) {
        AuthProperties.Oidc oidc = authProperties.getOidc();
        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(oidc.getJwkSetUri())) {
            decoder = NimbusJwtDecoder.withJwkSetUri(oidc.getJwkSetUri()).build();
        } else if (StringUtils.hasText(oidc.getIssuerUri())) {
            decoder = NimbusJwtDecoder.withIssuerLocation(oidc.getIssuerUri()).build();
        } else {
            throw new IllegalStateException("substrate.auth.mode=oidc requires"
                    + " substrate.auth.oidc.issuer-uri (or .jwk-set-uri) — the engagement's"
                    + " IdP is configuration, never code (production plan §4)");
        }

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(StringUtils.hasText(oidc.getIssuerUri())
                ? JwtValidators.createDefaultWithIssuer(oidc.getIssuerUri())
                : JwtValidators.createDefault());
        if (StringUtils.hasText(oidc.getAudience())) {
            String audience = oidc.getAudience();
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(audience)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    public BearerTokenResolver substrateBearerTokenResolver(AuthProperties authProperties) {
        return new SseTokenBearerTokenResolver(
                authProperties.getOidc().getSse().getTokenQueryPaths());
    }
}
