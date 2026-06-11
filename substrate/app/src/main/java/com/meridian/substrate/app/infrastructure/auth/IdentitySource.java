package com.meridian.substrate.app.infrastructure.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy seam of the {@code substrate.auth.mode} ladder: exactly ONE bean is
 * active per boot — {@link AnonymousIdentitySource} (default) or
 * {@link OidcIdentitySource} — selected by {@code @ConditionalOnProperty}.
 * {@code PersonaInterceptor} calls it once per {@code /api/**} request and
 * projects the result onto {@code PersonaContext} +
 * {@link SubstrateIdentityContext}.
 */
public interface IdentitySource {

    SubstrateIdentity resolve(HttpServletRequest request);
}
