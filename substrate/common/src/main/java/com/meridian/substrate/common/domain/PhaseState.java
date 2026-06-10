package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Lifecycle state for a domain at one phase. A domain has one row per phase;
 * the full set describes where the customer is in the redesign lifecycle
 * (doc 02). PhaseStateService manages transitions — Phase 1 ships the entity
 * and a placeholder service only; full checkpoint logic lands in later phases.
 */
@Entity
@Table(name = "phase_states",
        uniqueConstraints = @UniqueConstraint(name = "uk_phase_states_domain_phase",
                columnNames = {"domain_id", "phase"}))
public class PhaseState extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 50)
    private LifecyclePhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PhaseStatus status = PhaseStatus.NOT_STARTED;

    @Column(name = "entered_at")
    private Instant enteredAt;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "blockers", columnDefinition = "TEXT")
    private JsonNode blockers;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "checkpoints", columnDefinition = "TEXT")
    private JsonNode checkpoints;

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public LifecyclePhase getPhase() { return phase; }
    public void setPhase(LifecyclePhase phase) { this.phase = phase; }

    public PhaseStatus getStatus() { return status; }
    public void setStatus(PhaseStatus status) { this.status = status; }

    public Instant getEnteredAt() { return enteredAt; }
    public void setEnteredAt(Instant enteredAt) { this.enteredAt = enteredAt; }

    public JsonNode getBlockers() { return blockers; }
    public void setBlockers(JsonNode blockers) { this.blockers = blockers; }

    public JsonNode getCheckpoints() { return checkpoints; }
    public void setCheckpoints(JsonNode checkpoints) { this.checkpoints = checkpoints; }
}
