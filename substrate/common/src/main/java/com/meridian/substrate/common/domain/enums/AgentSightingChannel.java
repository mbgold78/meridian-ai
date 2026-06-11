package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Evidence channel of an {@code agent_sightings} row (engine-spec agents.md
 * §3/§10; ADR-003 D11). {@code last_seen_at}/{@code last_seen_via} update ONLY
 * from sighting writes on these channels — never from registration row recency
 * (invariant I7).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentSightingChannel {
    GATEWAY_ACTION,
    HEARTBEAT,
    METRIC_REPORT,
    ATTESTATION,
    DISPATCH_EVENT;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
