package com.meridian.substrate.app.infrastructure.web;

import com.meridian.substrate.app.infrastructure.auth.IdentitySource;
import com.meridian.substrate.app.infrastructure.auth.SubstrateIdentity;
import com.meridian.substrate.app.infrastructure.auth.SubstrateIdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the caller's {@link SubstrateIdentity} through the active
 * {@link IdentitySource} (the {@code substrate.auth.mode} ladder) and projects
 * it onto the request-scoped {@link PersonaContext} — Phase 5's single
 * adaptation point (port-map S9): every existing PersonaContext consumer
 * (controllers, actor mappers, envelope meta) is untouched.
 *
 * <ul>
 *   <li><b>anonymous</b> (default): {@code AnonymousIdentitySource} returns
 *       the verbatim {@code X-Persona-*} headers — projection semantics are
 *       byte-for-byte the pre-Phase-5 interceptor (level set only when the
 *       header was present, id/label set verbatim including {@code null}).</li>
 *   <li><b>oidc</b>: {@code OidcIdentitySource} derives everything from the
 *       verified JWT; persona headers are ignored (WARN-logged). The
 *       projected persona id IS the token {@code sub}, which is how actor
 *       fields and hash-chain entries pick up the IAM-stable id with zero
 *       consumer changes (ADR-001 D11).</li>
 * </ul>
 */
@Component
public class PersonaInterceptor implements HandlerInterceptor {

    private final PersonaContext personaContext;
    private final SubstrateIdentityContext identityContext;
    private final IdentitySource identitySource;

    public PersonaInterceptor(PersonaContext personaContext,
                              SubstrateIdentityContext identityContext,
                              IdentitySource identitySource) {
        this.personaContext = personaContext;
        this.identityContext = identityContext;
        this.identitySource = identitySource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        SubstrateIdentity identity = identitySource.resolve(request);
        identityContext.setIdentity(identity);
        if (identity.personaLevel() != null) {
            personaContext.setPersonaLevel(identity.personaLevel());
        }
        personaContext.setPersonaId(identity.principalId());
        personaContext.setPersonaLabel(identity.displayLabel());
        return true;
    }
}
