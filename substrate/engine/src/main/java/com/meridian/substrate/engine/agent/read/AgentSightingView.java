package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;

import java.time.Instant;
import java.util.UUID;

/**
 * One telemetry sighting ({@code GET /agents/{idOrKey}/sightings?since} —
 * engine-spec agents.md §7.1/§10; ADR-003 D11): telemetry with a retention
 * profile, deliberately NOT the audit log.
 */
public record AgentSightingView(
        UUID id,
        Instant seenAt,
        AgentSightingChannel channel,
        String detail) {

    public static AgentSightingView from(AgentSighting s) {
        return new AgentSightingView(s.getId(), s.getSeenAt(), s.getChannel(), s.getDetail());
    }
}
