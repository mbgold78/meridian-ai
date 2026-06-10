package com.meridian.substrate.app.branding;

import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimum-viable admin gate for branding-config writes and asset uploads.
 * Reads the request-scoped {@link PersonaContext}; allows the request through
 * iff {@code X-Persona-Level: ADMIN} (case-insensitive) is present.
 *
 * <p><b>Phase 1 limitation — port-map seam S9:</b> persona level is a
 * stringly-typed header that any client can set; there is no real authn/authz
 * framework yet ({@code substrate.auth.mode=anonymous}). This class is the
 * smallest boundary that makes the admin-only contract enforceable in code so
 * the intent does not silently regress; it is not a security control. Phase 5
 * replaces the identity source with the OIDC principal and real roles.
 */
@Component
public class BrandingAdminAuthorizer {

    public static final String ADMIN_PERSONA_LEVEL = "ADMIN";

    private final PersonaContext personaContext;

    public BrandingAdminAuthorizer(PersonaContext personaContext) {
        this.personaContext = personaContext;
    }

    public void requireAdmin() {
        String level = personaContext.getPersonaLevel();
        if (level == null || !ADMIN_PERSONA_LEVEL.equalsIgnoreCase(level)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Branding writes require an admin persona");
        }
    }
}
