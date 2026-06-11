package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Supervision posture from the AgentDeploymentSpec unification (engine-spec
 * agents.md §3; port-map §6 [AGENT-SPEC-UNIFICATION]; ADR-003 D3). Recorded,
 * displayed and published ONLY — substrate enforces nothing it would have to
 * invent: HUMAN_REVIEW_ABOVE_THRESHOLD threshold semantics are defined nowhere
 * (EXTENSION POINT(agent-4), spec §17 #4).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentSupervisoryModel {
    FULL_AUTO,
    HUMAN_REVIEW_ABOVE_THRESHOLD,
    HUMAN_IN_LOOP;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
