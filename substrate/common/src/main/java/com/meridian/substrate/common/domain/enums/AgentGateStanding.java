package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Denormalized standing of a criteria gate (engine-spec agents.md §3/§9;
 * ADR-003 D9). Signal-only — standing NEVER auto-pauses or auto-kills an
 * agent; INDETERMINATE evaluation (missing samples) never marks AT_RISK
 * (contracts D10 invariant carried over) — standing stays UNEVALUATED.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentGateStanding {
    UNEVALUATED,
    MET,
    AT_RISK,
    HUMAN_VERIFY_DUE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
