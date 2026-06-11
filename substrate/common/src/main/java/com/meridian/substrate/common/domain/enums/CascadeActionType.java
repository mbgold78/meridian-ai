package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Action verb recorded on an append-only
 * {@link com.meridian.substrate.common.domain.CascadeLevelAction} row
 * (engine-spec cascade.md §2, §5).
 *
 * <p>{@code DECLINE} is a legitimate accountability act, not a failure —
 * the spec's pinned contract guarantees DECLINE flows return HTTP 200
 * (decline ≠ error, ADR-001 non-negotiable #1).
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}.
 */
public enum CascadeActionType {
    ISSUE,
    CASCADE,
    ABSORB,
    DECLINE,
    RECASCADE,
    RESOLVE_LOOKS_GOOD,
    RESOLVE_DECLINED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
