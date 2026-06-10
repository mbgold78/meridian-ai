package com.meridian.substrate.app.api.v1.controller;

/**
 * Maps the web layer's request-scoped
 * {@link com.meridian.substrate.app.infrastructure.web.PersonaContext} onto the
 * engine module's plain {@link com.meridian.substrate.engine.ai.persona.PersonaContext}
 * value holder at the controller boundary.
 *
 * <p><b>Port note (chunk C5 wiring):</b> homepoc had a single request-scoped
 * {@code PersonaContext} living in the app AI package, which controllers passed
 * straight into the orchestrator via {@code snapshot()}. In substrate the engine
 * module carries no spring-web dependency, so the engine type is a POJO and the
 * web bean is mapped onto it here — producing a detached copy with the same
 * thread-safety property {@code snapshot()} provided in homepoc.
 */
final class Personas {

    private Personas() {
    }

    /** Detached engine-side persona copy of the current request's persona context. */
    static com.meridian.substrate.engine.ai.persona.PersonaContext toEngine(
            com.meridian.substrate.app.infrastructure.web.PersonaContext webPersona) {
        return new com.meridian.substrate.engine.ai.persona.PersonaContext(
                webPersona.getPersonaLevel(), webPersona.getPersonaId());
    }
}
