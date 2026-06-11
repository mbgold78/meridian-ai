package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.enums.AgentObservability;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.AgentStaleness;

import java.time.Instant;

/**
 * The detail DTO's derived liveness block (engine-spec agents.md §7.3/§10;
 * ADR-003 D11): strictly evidence-derived from sightings + attestation —
 * registration {@code updated_at} is NEVER conflated with liveness (invariant
 * I7), and {@code NOT_EXPECTED} never renders stale-red. UI rule binding for
 * the swap: the status pill is governance state; liveness is a SEPARATE line
 * ("Last seen 2h ago via gateway" / "Not observable — last attested 12 days
 * ago by RCM Lead") — never a fabricated green dot.
 */
public record AgentLivenessView(
        Instant lastSeenAt,
        AgentSightingChannel lastSeenVia,
        Instant lastAttestedAt,
        String lastAttestedBy,
        AgentObservability observability,
        AgentStaleness staleness,
        Integer heartbeatExpectedMinutes) {
}
