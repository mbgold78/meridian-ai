package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Aggregate lifecycle state of a {@link com.meridian.substrate.common.domain.Cascade}
 * (engine-spec cascade.md §2, [CASCADE-LIFECYCLE]).
 *
 * <p>Exactly the binding three states. A documented Decline is a LEGITIMATE
 * terminal resolution — {@code RESOLVED_DECLINED} is a peer terminal state to
 * {@code RESOLVED_LOOKS_GOOD}, never an error/failure state. No ERROR/FAILED
 * value exists in this enum by design (ADR-001 non-negotiable #1).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} ({@code in-flight}, {@code resolved-looks-good},
 * {@code resolved-declined}) — the fixture vocabulary (ADR D13).
 */
public enum CascadeState {
    IN_FLIGHT,
    RESOLVED_LOOKS_GOOD,
    RESOLVED_DECLINED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
