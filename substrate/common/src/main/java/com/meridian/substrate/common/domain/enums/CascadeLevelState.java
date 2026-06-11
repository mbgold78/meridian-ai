package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Per-level state of a {@link com.meridian.substrate.common.domain.CascadeLevel}
 * (engine-spec cascade.md §2, [CASCADE-LEVELS]).
 *
 * <p>Exactly the binding four states — endorse-decline is modeled as
 * CASCADED→DECLINED with the upper level's own reasoning, NOT a fifth state
 * (ADR D4). No ERROR/FAILED value exists (decline is never an error).
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}
 * ({@code looking}, {@code absorbed}, {@code cascaded}, {@code declined}).
 */
public enum CascadeLevelState {
    // EXTENSION POINT(cascade-1): LOOKING is encoded at one-sentence fidelity only —
    // initial state on activation (+ overdue notification overlay). Reassignment,
    // expiry, delegation and dwell semantics are deliberately NOT designed (spec §13.1).
    LOOKING,
    // EXTENSION POINT(cascade-2): ABSORBED is encoded at one-sentence fidelity only —
    // terminates downward propagation, refinedCritique required, cascade stays
    // in-flight pending originator resolution. Progress tracking, completion evidence
    // and originator sign-off requirements are deliberately NOT designed (spec §13.2).
    ABSORBED,
    CASCADED,
    DECLINED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
