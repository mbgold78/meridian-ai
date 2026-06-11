package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Transactional-outbox notification vocabulary ({@code agent_notifications};
 * engine-spec agents.md §3/§13.4; ADR-003 D13). Rows are written in the SAME
 * transaction as the triggering event and fan out to the ordered oversight
 * chain.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentNotificationType {
    AGENT_DEPLOYED,
    INTERVENTION_OPENED,
    INTERVENTION_ESCALATED,
    CONTROL_REQUESTED,
    CONTROL_UNCONFIRMED,
    CONTROL_VIOLATION,
    PROMOTION_REQUESTED,
    PROMOTION_DECIDED,
    CRITERIA_AT_RISK,
    HUMAN_VERIFY_DUE,
    AGENT_RETIRED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
