package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.enums.AgentControlConfirmationState;

import java.time.Instant;

/**
 * The detail DTO's {@code enforcement} block (engine-spec agents.md §7.3):
 * RECORDED_INTENT control-confirmation tracking — runtime acknowledgment
 * (M17) and human-attested confirmation (M16) are DISTINCT facts; neither
 * auto-derives from the other and sighting absence flips NOTHING (ADR-003
 * D6). All-null when no control is pending ({@code confirmationState}
 * {@code not-required}).
 */
public record AgentControlTracking(
        String pendingVerb,
        AgentControlConfirmationState confirmationState,
        Instant requestedAt,
        Instant acknowledgedAt,
        Instant confirmedAt,
        String confirmedBy) {
}
