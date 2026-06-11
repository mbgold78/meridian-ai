package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Lifecycle of an {@code agent_interventions} attention-overlay row
 * (engine-spec agents.md §3/§5.1 M11–M13; ADR-003 D4). An open intervention
 * does NOT change {@code operational_mode} — pausing the agent is a separate,
 * separately-evented operator act.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentInterventionStatus {
    OPEN,
    RESOLVED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
