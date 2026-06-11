package com.meridian.substrate.common.domain;

import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Liveness telemetry, NOT audit (engine-spec agents.md §4 table 10 / §10;
 * ADR-003 D11): deliberately separate from {@code agent_status_events} —
 * sightings have a retention profile (config reserved, spec §1 / §17 #15);
 * events are the governance trail. Mixing them would bloat the append-only
 * audit and poison the D14 chain-anchor seam.
 *
 * <p>Hourly-bucket deduped: {@code UNIQUE(agent, channel, bucket_key)}
 * (bucket width {@code substrate.agents.sighting-bucket-minutes});
 * duplicate-key violations are swallowed as no-ops at the service.
 */
@Entity
@Table(name = "agent_sightings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_as_bucket",
                        columnNames = {"agent_registration_id", "channel", "bucket_key"})
        })
public class AgentSighting extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 30)
    private AgentSightingChannel channel;

    @Column(name = "detail", length = 500)
    private String detail;

    /** Hourly dedupe bucket key (config §1). */
    @Column(name = "bucket_key", nullable = false, length = 40)
    private String bucketKey;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public Instant getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(Instant seenAt) {
        this.seenAt = seenAt;
    }

    public AgentSightingChannel getChannel() {
        return channel;
    }

    public void setChannel(AgentSightingChannel channel) {
        this.channel = channel;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getBucketKey() {
        return bucketKey;
    }

    public void setBucketKey(String bucketKey) {
        this.bucketKey = bucketKey;
    }
}
