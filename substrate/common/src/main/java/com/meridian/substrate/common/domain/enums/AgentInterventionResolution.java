package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a human resolved an intervention (engine-spec agents.md §3/§5.1 M13).
 * Every value is a legitimate governance outcome — resolution is mandatory at
 * resolve time together with a resolution note, and renders neutral/info.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentInterventionResolution {
    RESUMED,
    RECONFIGURED,
    DEMOTED_TO_SHADOW,
    RETIRED,
    NO_ACTION;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
