package com.meridian.substrate.app.infrastructure.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped bean holding the current persona level and identity.
 * Populated by {@link PersonaInterceptor} from the spoofable
 * {@code X-Persona-Level} / {@code X-Persona-Id} headers — Phase 1
 * anonymous-auth mode only (port-map S9); OIDC replaces this as the
 * identity source in Phase 5.
 *
 * <p><b>Placement note (deviation from homepoc):</b> homepoc kept this class at
 * {@code app.ai.persona}, which the rename plan maps into the engine module
 * (chunk C3). The engine module carries no spring-web dependency and lands after
 * this chunk, so the request-scoped holder lives here in the web layer. Engine
 * services should receive persona level/id as explicit inputs at the controller
 * boundary (chunk C5 wiring) rather than injecting this bean.
 */
@Component
@RequestScope
public class PersonaContext {

    private String personaLevel = "L3_ANALYST";
    private String personaId;

    public String getPersonaLevel() {
        return personaLevel;
    }

    public void setPersonaLevel(String personaLevel) {
        this.personaLevel = personaLevel;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    /** Returns a plain detached copy safe to use across thread boundaries. */
    public PersonaContext snapshot() {
        PersonaContext copy = new PersonaContext();
        copy.personaLevel = this.personaLevel;
        copy.personaId = this.personaId;
        return copy;
    }
}
