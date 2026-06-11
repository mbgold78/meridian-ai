package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * APPEND-ONLY audit source of truth for every agent lifecycle / control /
 * oversight fact (engine-spec agents.md §4 table 3; ADR-003 D1; spec
 * non-negotiable #3 / invariant I4). Rows are NEVER updated or deleted: the
 * repository surface ({@code AgentStatusEventRepository} extends plain
 * {@code Repository}) exposes no mutators, services never mutate loaded rows,
 * and on Postgres the V6_1 trigger raises on any UPDATE/DELETE. H2 dev is
 * service-enforced only (precedent V2_1/V4_1; H2-green is never acceptance).
 *
 * <p>Every event carries actor (type + label + soft member id),
 * {@code occurredAt} from the injected Clock, and a gapless per-agent
 * {@code eventSeq} with UNIQUE backstop (invariant I3). Lifecycle events
 * never carry {@code actorType=AGENT} — service-enforced, no MCP lifecycle
 * tools exist (invariant I11).
 *
 * <p>Payload conventions (§4): DEPLOYED = full frozen spec snapshot;
 * OVERSIGHT_CHANGED = before/after; THROTTLE_SET = budget; DISPATCH =
 * provenance digest; PROMOTED = consumed review id. Reason is mandatory for
 * PAUSED/KILLED/RETIRED (DB CHECK {@code ck_ase_reason}).
 */
@Entity
@Table(name = "agent_status_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ase_idem",
                        columnNames = {"agent_registration_id", "idempotency_key"}),
                @UniqueConstraint(name = "uq_ase_seq",
                        columnNames = {"agent_registration_id", "event_seq"})
        })
public class AgentStatusEvent extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    /** Soft ref → domains; no DB FK (cross-fleet feed queries). */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /** 1..N per agent, gapless (invariant I3); UNIQUE(agent, event_seq) backstop. */
    @Column(name = "event_seq", nullable = false)
    private int eventSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AgentEventType eventType;

    /** ContractActorType reused (spec §3); AGENT illegal on lifecycle events (service, I11). */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private ContractActorType actorType;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "actor_member_id")
    private UUID actorMemberId;

    /** PersonaContext now (S9 spoofable, accepted), OIDC Phase 5 — no schema change. */
    @Column(name = "actor_label", nullable = false)
    private String actorLabel;

    /** Mandatory for PAUSED/KILLED/RETIRED (DB CHECK ck_ase_reason). */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "payload", columnDefinition = "TEXT")
    private JsonNode payload;

    /** JSON array of Evidence/DocumentRegistry ids (soft refs). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence_refs", columnDefinition = "TEXT")
    private JsonNode evidenceRefs;

    /** Mandatory client key; UNIQUE(agent, idempotency_key) — cascade D8 idiom. */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** From the injected Clock (invariant I3). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Set when mirrored into the decision-log chain (6b: DEPLOYED/PROMOTED/RETIRED/KILLED). */
    @Column(name = "decision_log_hash", length = 64)
    private String decisionLogHash;

    // -------------------------------------------------------------- accessors
    // (setters exist for construction only — service discipline + the
    // Repository-not-JpaRepository surface + the V6_1 PG trigger keep rows
    // append-only)

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public int getEventSeq() {
        return eventSeq;
    }

    public void setEventSeq(int eventSeq) {
        this.eventSeq = eventSeq;
    }

    public AgentEventType getEventType() {
        return eventType;
    }

    public void setEventType(AgentEventType eventType) {
        this.eventType = eventType;
    }

    public ContractActorType getActorType() {
        return actorType;
    }

    public void setActorType(ContractActorType actorType) {
        this.actorType = actorType;
    }

    public UUID getActorMemberId() {
        return actorMemberId;
    }

    public void setActorMemberId(UUID actorMemberId) {
        this.actorMemberId = actorMemberId;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public JsonNode getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(JsonNode evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getDecisionLogHash() {
        return decisionLogHash;
    }

    public void setDecisionLogHash(String decisionLogHash) {
        this.decisionLogHash = decisionLogHash;
    }
}
