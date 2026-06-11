package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AgentSighting} telemetry (engine-spec agents.md
 * §2/§4 table 10 / §10; ADR-003 D11). Sightings are telemetry with a
 * retention profile — deliberately NOT the audit log.
 */
@Repository
public interface AgentSightingRepository extends JpaRepository<AgentSighting, UUID> {

    /** M3 shadow-window summary input (spec §5.1: sighting count over the window). */
    long countByAgentRegistrationId(UUID agentRegistrationId);

    /** Latest ATTESTATION-channel sighting — the rebuilder's last_attested_at source (§10/I7). */
    Optional<AgentSighting> findFirstByAgentRegistrationIdAndChannelOrderBySeenAtDesc(
            UUID agentRegistrationId, AgentSightingChannel channel);

    List<AgentSighting> findByAgentRegistrationIdOrderBySeenAtDesc(UUID agentRegistrationId);

    /** {@code GET /agents/{idOrKey}/sightings?since} lane (idx_as_agent_seen). */
    List<AgentSighting> findByAgentRegistrationIdAndSeenAtAfterOrderBySeenAtDesc(
            UUID agentRegistrationId, Instant since);

    /** Latest sighting — the first {@code recentActivity} derivation source (spec §7.3). */
    Optional<AgentSighting> findFirstByAgentRegistrationIdOrderBySeenAtDesc(UUID agentRegistrationId);

    /**
     * Latest sighting carrying a human-readable detail — the §7.3
     * {@code recentActivity} chain reads DETAILS (detail-less telemetry rows,
     * e.g. METRIC_REPORT, still count for liveness but never render).
     */
    Optional<AgentSighting> findFirstByAgentRegistrationIdAndDetailIsNotNullOrderBySeenAtDesc(
            UUID agentRegistrationId);

    /** Hourly-bucket dedupe pre-check (uq_as_bucket is the backstop; violations swallowed as no-op). */
    boolean existsByAgentRegistrationIdAndChannelAndBucketKey(UUID agentRegistrationId,
                                                              AgentSightingChannel channel,
                                                              String bucketKey);
}
