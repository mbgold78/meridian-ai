package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.engine.contract.registry.ContractActor;

/**
 * Maps the request-scoped persona headers onto the contract engine's acting
 * identity (engine-spec contracts.md §3.2 table 13). REST callers are always
 * HUMAN-typed principals pre-Phase-5: persona headers are the only identity
 * source (spoofable, recorded verbatim — port-map S9; spec §15 #12), and
 * agent principals reach the engine ONLY over the governed MCP surface (3c)
 * where {@code X-MCP-Principal} maps to AGENT. OIDC replaces the source in
 * Phase 5 with no schema change.
 *
 * <p>Identity fallback chain: {@code X-Persona-Id} → {@code X-Persona-Label}
 * → persona level (never blank — {@code ContractActor} requires a non-blank
 * id, and {@link PersonaContext} defaults the level).
 */
final class PersonaActors {

    private PersonaActors() {
    }

    static ContractActor human(PersonaContext personaContext) {
        return ContractActor.human(identity(personaContext));
    }

    /** The verbatim identity string also recorded as attestedBy/respondedBy/reportedBy. */
    static String identity(PersonaContext personaContext) {
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
