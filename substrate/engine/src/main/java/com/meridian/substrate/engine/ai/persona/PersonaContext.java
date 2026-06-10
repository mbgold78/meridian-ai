package com.meridian.substrate.engine.ai.persona;

/**
 * Holds the persona level and identity for an engine AI invocation.
 *
 * <p><b>Placement note (deviation from homepoc):</b> homepoc declared this class
 * {@code @Component @RequestScope} in the app module's AI package. In substrate the
 * engine module carries no spring-web dependency, so this is a plain value holder —
 * the app web layer (chunk B3's request-scoped
 * {@code com.meridian.substrate.app.infrastructure.web.PersonaContext}) is mapped
 * onto this type at the controller boundary (chunk C5 wiring). This also avoids a
 * Spring bean-name collision between two component-scanned {@code PersonaContext}
 * classes.
 */
public class PersonaContext {

    private String personaLevel = "L3_ANALYST";
    private String personaId;

    public PersonaContext() {
    }

    public PersonaContext(String personaLevel, String personaId) {
        this.personaLevel = personaLevel;
        this.personaId = personaId;
    }

    /** Convenience factory for a persona identified only by level. */
    public static PersonaContext ofLevel(String personaLevel) {
        return new PersonaContext(personaLevel, null);
    }

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
