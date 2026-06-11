package com.meridian.substrate.app.infrastructure.web;

import com.meridian.substrate.engine.contract.gateway.GovernedPrincipalSource;
import com.meridian.substrate.engine.contract.gateway.Principal;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * The app module's transport principal for the §4.2 PEP-2 aspect (engine-spec
 * contracts.md §4.2: "resolves principal from {@code PersonaContext}
 * (HUMAN)"): while a servlet request is active, REST callers are HUMAN-typed
 * principals whose identity is the persona-header fallback chain
 * ({@code X-Persona-Id} → {@code X-Persona-Label} → persona level — the
 * {@code PersonaActors} chain, kept in lockstep), recorded verbatim and
 * spoofable pre-Phase-5 (port-map S9; spec §15 #12).
 *
 * <p>Outside a request (DemoPack seeding, schedulers) this source yields
 * empty and the aspect falls back to SERVICE. MCP write-tool calls are
 * AGENT-scoped explicitly via {@code GovernedPrincipalScope}, which the
 * aspect consults BEFORE any source — this bean can never misattribute an
 * MCP call.
 */
@Component
public class PersonaGovernedPrincipalSource implements GovernedPrincipalSource {

    private final PersonaContext personaContext;

    public PersonaGovernedPrincipalSource(PersonaContext personaContext) {
        this.personaContext = personaContext; // request-scoped TARGET_CLASS proxy
    }

    @Override
    public Optional<Principal> currentPrincipal() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Principal.human(identity()));
        } catch (ScopeNotActiveException outsideRequest) {
            return Optional.empty();
        }
    }

    /** Mirrors {@code PersonaActors.identity} (controller package-private). */
    private String identity() {
        if (notBlank(personaContext.getPersonaId())) {
            return personaContext.getPersonaId();
        }
        if (notBlank(personaContext.getPersonaLabel())) {
            return personaContext.getPersonaLabel();
        }
        return personaContext.getPersonaLevel();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
