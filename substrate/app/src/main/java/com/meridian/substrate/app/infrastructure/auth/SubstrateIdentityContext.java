package com.meridian.substrate.app.infrastructure.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped holder for the resolved {@link SubstrateIdentity} — the
 * roles-aware sibling of {@code PersonaContext} (which stays the persona-level
 * projection consumed by the existing controller surface). Populated by
 * {@code PersonaInterceptor} on every {@code /api/**} request; consulted by
 * admin gates ({@code BrandingAdminAuthorizer}) under oidc mode.
 */
@Component
@RequestScope
public class SubstrateIdentityContext {

    private SubstrateIdentity identity;

    /** Nullable before the interceptor ran (non-API paths never populate it). */
    public SubstrateIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(SubstrateIdentity identity) {
        this.identity = identity;
    }
}
