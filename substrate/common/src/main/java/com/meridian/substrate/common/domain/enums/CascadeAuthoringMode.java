package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Authoring shape of a cascade draft / issued cascade (engine-spec cascade.md
 * §2; ADR D6). All three shapes persist today; the leader authoring UX itself
 * is gated on the queue-#24 Mike session ([CASCADE-AUTHORING-GAP]).
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}
 * ({@code free-form}, {@code imperative}, {@code ai-suggested}).
 */
public enum CascadeAuthoringMode {
    FREE_FORM,
    IMPERATIVE,
    AI_SUGGESTED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
