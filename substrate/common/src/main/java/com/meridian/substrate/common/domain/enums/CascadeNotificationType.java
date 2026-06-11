package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Type of a {@link com.meridian.substrate.common.domain.CascadeNotification}
 * transactional-outbox row (engine-spec cascade.md §2, §8; ADR D10).
 *
 * <p>{@code LEVEL_DECLINED} / {@code DECLINE_PENDING_ORIGINATOR} /
 * {@code SIBLING_DECLINE} / {@code CASCADE_RESOLVED_DECLINED} carry
 * leader-attention or neutral semantics — decline is never an error.
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}.
 */
public enum CascadeNotificationType {
    CASCADE_ISSUED,
    LEVEL_ACTIVATED,
    LEVEL_CASCADED,
    LEVEL_ABSORBED,
    LEVEL_DECLINED,
    DECLINE_PENDING_ORIGINATOR,
    SIBLING_DECLINE,
    LEVEL_OVERDUE,
    DECLINE_OVERDUE,
    CASCADE_RESOLVED_LOOKS_GOOD,
    CASCADE_RESOLVED_DECLINED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
