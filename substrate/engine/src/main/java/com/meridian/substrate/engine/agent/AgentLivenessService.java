package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentObservability;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.AgentStaleness;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Honest liveness (engine-spec agents.md §10; ADR-003 D11): sighting
 * recording + the read-time observability/staleness derivations.
 *
 * <p>Invariant I7 lives here: {@code last_seen_at}/{@code last_seen_via}
 * change ONLY through {@link #recordSighting} — never from registration
 * updates; registration {@code updated_at} is NEVER conflated with liveness.
 * Sightings are telemetry with a retention profile (config reserved, §1),
 * deliberately NOT the audit log; recording one appends NO status event.
 *
 * <p>Channels (§3): GATEWAY_ACTION (6c, every allowed governed call),
 * HEARTBEAT (6c MCP), METRIC_REPORT ({@link AgentMetricService}), ATTESTATION
 * (REST attest-liveness M15 — 6b verb; the DemoPack seeder drives
 * {@link #recordAttestation} directly), DISPATCH_EVENT (§14 hooks).
 */
@Service
public class AgentLivenessService {

    private static final Logger log = LoggerFactory.getLogger(AgentLivenessService.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentSightingRepository sightingRepository;
    private final AgentProperties properties;
    private final Clock clock;

    public AgentLivenessService(AgentRegistrationRepository registrationRepository,
                                AgentSightingRepository sightingRepository,
                                AgentProperties properties,
                                Clock clock) {
        this.registrationRepository = registrationRepository;
        this.sightingRepository = sightingRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- writes

    /**
     * §12 {@code agent_heartbeat} engine half (6c): a HEARTBEAT-channel
     * sighting carrying the optional activity summary as its detail.
     * GOVERNED as {@code agent.heartbeat} — the MCP adapter scopes the AGENT
     * principal, the §8 registry gate sees every call, and a suspended
     * principal's heartbeat is denied-but-recorded (zombie flow §6.4: the
     * gate sights the touch and signals the chain BEFORE the deny).
     */
    @Governed(actionType = GovernedActions.AGENT_HEARTBEAT)
    @Transactional
    public Optional<AgentSighting> heartbeat(UUID agentRegistrationId, String activitySummary) {
        return recordSighting(agentRegistrationId, AgentSightingChannel.HEARTBEAT,
                truncateDetail(activitySummary));
    }

    /**
     * Records one sighting at the injected clock's instant, deduped per
     * hourly bucket ({@code UNIQUE(agent, channel, bucket_key)}; §1
     * {@code sighting-bucket-minutes}); a duplicate within the bucket is a
     * no-op (the DB unique is the race backstop — violations swallowed,
     * §10). Updates the registration liveness projection only when this
     * sighting is the newest evidence.
     *
     * @return the persisted sighting, or empty on a bucket-dedupe no-op
     */
    @Ungoverned(reason = "telemetry write, not a lifecycle verb or ADR D2 PEP — sightings are"
            + " recorded as a side-channel of governed calls (6c), MCP heartbeats, metric reports"
            + " and human attestation; the governed lane wraps the CALLER, never this recorder"
            + " (engine-spec agents.md §10)")
    @Transactional
    public Optional<AgentSighting> recordSighting(UUID agentRegistrationId,
                                                  AgentSightingChannel channel,
                                                  String detail) {
        AgentRegistration reg = registrationRepository.findByIdForUpdate(agentRegistrationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent registration not found: " + agentRegistrationId));
        Instant seenAt = clock.instant();
        String bucketKey = bucketKey(seenAt);
        if (sightingRepository.existsByAgentRegistrationIdAndChannelAndBucketKey(
                reg.getId(), channel, bucketKey)) {
            return Optional.empty();
        }
        AgentSighting sighting = new AgentSighting();
        sighting.setAgentRegistrationId(reg.getId());
        sighting.setSeenAt(seenAt);
        sighting.setChannel(channel);
        sighting.setDetail(detail);
        sighting.setBucketKey(bucketKey);
        try {
            sighting = sightingRepository.save(sighting);
        } catch (DataIntegrityViolationException raced) {
            // uq_as_bucket backstop — a concurrent recorder won the bucket (§10).
            log.debug("Sighting bucket already taken [agent={}, channel={}, bucket={}]",
                    reg.getId(), channel, bucketKey);
            return Optional.empty();
        }
        if (reg.getLastSeenAt() == null || seenAt.isAfter(reg.getLastSeenAt())) {
            reg.setLastSeenAt(seenAt);
            reg.setLastSeenVia(channel);
            registrationRepository.save(reg);
        }
        return Optional.of(sighting);
    }

    /**
     * Human attestation slice of M15 ("I verified this agent is running"):
     * records the ATTESTATION sighting AND the {@code last_attested_*}
     * projection. The full attest-liveness verb (LIVENESS_ATTESTED event +
     * evidence refs) is 6b; the DemoPack seeder uses this lane to seed the
     * fixture {@code recentActivity} strings as sighting details (§15).
     */
    @Ungoverned(reason = "telemetry write, not a lifecycle verb or ADR D2 PEP — the 6b"
            + " attest-liveness REST verb wraps this with its LIVENESS_ATTESTED event"
            + " (engine-spec agents.md §5.1 M15 / §10)")
    @Transactional
    public Optional<AgentSighting> recordAttestation(UUID agentRegistrationId,
                                                     String attestedByLabel,
                                                     String detail) {
        Optional<AgentSighting> sighting =
                recordSighting(agentRegistrationId, AgentSightingChannel.ATTESTATION, detail);
        sighting.ifPresent(s -> {
            AgentRegistration reg = registrationRepository.findByIdForUpdate(agentRegistrationId)
                    .orElseThrow();
            if (reg.getLastAttestedAt() == null || !s.getSeenAt().isBefore(reg.getLastAttestedAt())) {
                reg.setLastAttestedAt(s.getSeenAt());
                reg.setLastAttestedByLabel(attestedByLabel);
                registrationRepository.save(reg);
            }
        });
        return sighting;
    }

    // ------------------------------------------------- read-time derivations

    /**
     * §10: OBSERVABLE if a telemetry channel exists — a bound MCP principal,
     * or ORCHESTRATOR locus (dispatch wiring) — else ATTEST_ONLY
     * (CUSTOMER_SYSTEM without MCP binding).
     */
    public AgentObservability observabilityOf(AgentRegistration reg) {
        boolean channelExists = reg.getMcpPrincipalId() != null
                || reg.getExecutionLocus() == AgentExecutionLocus.ORCHESTRATOR;
        return channelExists ? AgentObservability.OBSERVABLE : AgentObservability.ATTEST_ONLY;
    }

    /**
     * §10 staleness: NOT_EXPECTED if no channel and no expectation (never
     * stale-red); NEVER_SEEN if a channel/expectation exists and no sighting
     * ever; STALE if {@code heartbeat_expected_minutes} is set and exceeded,
     * or unset-but-channel-exists and {@code stale-after-hours} exceeded;
     * else FRESH. Derives from sighting evidence ONLY (I7).
     */
    public AgentStaleness stalenessOf(AgentRegistration reg) {
        boolean channelExists = observabilityOf(reg) == AgentObservability.OBSERVABLE;
        Integer expectedMinutes = reg.getHeartbeatExpectedMinutes();
        Instant lastSeen = reg.getLastSeenAt();
        if (lastSeen == null) {
            if (!channelExists && expectedMinutes == null) {
                return AgentStaleness.NOT_EXPECTED;
            }
            return AgentStaleness.NEVER_SEEN;
        }
        Instant now = clock.instant();
        if (expectedMinutes != null) {
            return lastSeen.plus(Duration.ofMinutes(expectedMinutes)).isBefore(now)
                    ? AgentStaleness.STALE : AgentStaleness.FRESH;
        }
        if (channelExists && lastSeen.plus(Duration.ofHours(
                properties.getLiveness().getStaleAfterHours())).isBefore(now)) {
            return AgentStaleness.STALE;
        }
        return AgentStaleness.FRESH;
    }

    /** Sighting {@code detail} is VARCHAR(500) — MCP summaries are clipped, never rejected. */
    private static String truncateDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String trimmed = detail.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    /** Hourly-bucket key (config §1; epoch-bucketed so it is zone-free and ≤40 chars). */
    private String bucketKey(Instant seenAt) {
        long bucketSeconds = Math.max(1, properties.getSightingBucketMinutes()) * 60L;
        return Long.toString(seenAt.getEpochSecond() / bucketSeconds);
    }
}
