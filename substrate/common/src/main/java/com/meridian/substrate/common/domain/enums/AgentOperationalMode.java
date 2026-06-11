package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Machine state of an agent registration (engine-spec agents.md §3/§5; ADR-003
 * D4). One of the TWO state dimensions: open interventions are a separate
 * attention overlay ({@code agent_interventions} rows) — an in-intervention
 * agent typically keeps its operational mode (still RUNNING). RETIRED is
 * terminal-absorbing, reached by kill (emergency) or retire (orderly), both
 * legitimate human governance acts (HTTP 200, never error-colored).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentOperationalMode {
    SHADOW,
    LIVE,
    PAUSED,
    RETIRED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
