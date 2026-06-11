package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * APPEND-ONLY high-volume PDP decision log row (engine-spec contracts.md §3.2
 * table 14; ADR-002 D17) — kept separate from {@code contract_events}
 * (different volume/retention profile; §15 extension point #11: no retention
 * policy yet). Insert-only repository surface
 * ({@link com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository}
 * extends {@code Repository}, spec §10.9).
 *
 * <p>DENY rows are written in {@code REQUIRES_NEW} so they survive the
 * business-transaction rollback (§10.4). {@code contractIds}/{@code clauseIds}
 * are JSON arrays — soft refs, NO DB FKs; queried by domain+time in v1
 * (application-enforced, comment-pinned per spec §3.1).
 */
@Entity
@Table(name = "contract_enforcement_decisions")
public class ContractEnforcementDecision extends BaseEntity {

    /** Engagement org scope. Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    /** Claimed principal type, recorded verbatim (spoofable pre-Phase-5, §15 #12). */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private PrincipalType actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 32)
    private GatewayDecision decision;

    /** JSON array of contract UUIDs (soft refs; no FK — queried by domain+time in v1). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "contract_ids", columnDefinition = "TEXT")
    private JsonNode contractIds;

    /** JSON array of guard ids (soft refs). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "clause_ids", columnDefinition = "TEXT")
    private JsonNode clauseIds;

    /** Orchestrator dispatch id when actionType=apply_dispatch_result. */
    @Column(name = "dispatch_id")
    private String dispatchId;

    @Column(name = "reason", length = 512)
    private String reason;

    /** Measured around gateway evaluate only (§10.4). */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** Enforcement mode at decision time: 'shadow' | 'enforce' (ADR D4). */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    // -------------------------------------------------------------- accessors

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public PrincipalType getActorType() {
        return actorType;
    }

    public void setActorType(PrincipalType actorType) {
        this.actorType = actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public GatewayDecision getDecision() {
        return decision;
    }

    public void setDecision(GatewayDecision decision) {
        this.decision = decision;
    }

    public JsonNode getContractIds() {
        return contractIds;
    }

    public void setContractIds(JsonNode contractIds) {
        this.contractIds = contractIds;
    }

    public JsonNode getClauseIds() {
        return clauseIds;
    }

    public void setClauseIds(JsonNode clauseIds) {
        this.clauseIds = clauseIds;
    }

    public String getDispatchId() {
        return dispatchId;
    }

    public void setDispatchId(String dispatchId) {
        this.dispatchId = dispatchId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
