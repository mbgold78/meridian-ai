package com.meridian.substrate.app.branding;

import com.meridian.substrate.app.infrastructure.auth.AuthProperties;
import com.meridian.substrate.app.infrastructure.auth.SubstrateIdentity;
import com.meridian.substrate.app.infrastructure.auth.SubstrateIdentityContext;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin gate for branding-config writes and asset uploads — the Phase 5 ladder
 * (port-map S9; docs/auth.md §4):
 *
 * <ul>
 *   <li><b>{@code substrate.auth.mode=anonymous}</b> (Phase 1–4 posture,
 *       unchanged): allow iff {@code X-Persona-Level: ADMIN}
 *       (case-insensitive). Stringly-typed and spoofable — the smallest
 *       boundary that keeps the admin-only contract enforceable in code; not
 *       a security control.</li>
 *   <li><b>{@code substrate.auth.mode=oidc}</b>: the header is dead (ignored
 *       upstream with a WARN); the gate consults REAL roles from the verified
 *       JWT via {@link SubstrateIdentityContext} — allow iff the caller holds
 *       one of {@code substrate.auth.oidc.admin-roles} (default
 *       {@code substrate-admin}).</li>
 * </ul>
 */
@Component
public class BrandingAdminAuthorizer {

    public static final String ADMIN_PERSONA_LEVEL = "ADMIN";

    private final PersonaContext personaContext;
    private final SubstrateIdentityContext identityContext;
    private final AuthProperties authProperties;

    public BrandingAdminAuthorizer(PersonaContext personaContext,
                                   SubstrateIdentityContext identityContext,
                                   AuthProperties authProperties) {
        this.personaContext = personaContext;
        this.identityContext = identityContext;
        this.authProperties = authProperties;
    }

    public void requireAdmin() {
        if (authProperties.isOidc()) {
            SubstrateIdentity identity = identityContext.getIdentity();
            if (identity == null
                    || !identity.hasAnyRole(authProperties.getOidc().getAdminRoles())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Branding writes require an admin role");
            }
            return;
        }
        String level = personaContext.getPersonaLevel();
        if (level == null || !ADMIN_PERSONA_LEVEL.equalsIgnoreCase(level)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Branding writes require an admin persona");
        }
    }
}
