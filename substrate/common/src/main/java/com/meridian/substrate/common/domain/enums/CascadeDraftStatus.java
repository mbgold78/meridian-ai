package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Lifecycle status of a {@link com.meridian.substrate.common.domain.CascadeDraft}
 * (engine-spec cascade.md §2; ADR D6 — drafts live OUTSIDE the audit trail; a
 * cascade is born IN_FLIGHT only on human promotion via {@code /issue}).
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}.
 */
public enum CascadeDraftStatus {
    OPEN,
    ISSUED,
    DISCARDED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
