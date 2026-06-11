package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Event vocabulary of the APPEND-ONLY {@code agent_status_events} audit log
 * (engine-spec agents.md §3/§5.1; ADR-003 D1). PROMOTION_REJECTED and
 * PAUSED/KILLED/RETIRED are legitimate governance outcomes — info/warn toned,
 * never failure vocabulary (trap #8).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentEventType {
    DEPLOYED,
    PROMOTION_REQUESTED,
    PROMOTED,
    PROMOTION_REJECTED,
    PAUSED,
    RESUMED,
    THROTTLE_SET,
    THROTTLE_CLEARED,
    KILLED,
    RETIRED,
    INTERVENTION_OPENED,
    INTERVENTION_ACKNOWLEDGED,
    INTERVENTION_RESOLVED,
    OVERSIGHT_CHANGED,
    CONTROL_ACKNOWLEDGED,
    CONTROL_CONFIRMED,
    CONTROL_VIOLATION_OBSERVED,
    LIVENESS_ATTESTED,
    PRINCIPAL_BOUND,
    PRINCIPAL_REVOKED,
    CRITERIA_STANDING_CHANGED,
    DISPATCH;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
