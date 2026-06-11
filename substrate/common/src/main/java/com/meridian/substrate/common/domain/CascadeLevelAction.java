package com.meridian.substrate.common.domain;

import com.meridian.substrate.common.domain.enums.CascadeActionType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * APPEND-ONLY action log row — the audit-trail source of truth for everything
 * mutable on a cascade (engine-spec cascade.md §3.3; ADR D1/D9;
 * [CASCADE-DURABILITY]: "the level chain IS the audit trail, authored as the
 * work happens, not retrofitted").
 *
 * <p>Rows are NEVER updated or deleted (invariant I8): the repository surface
 * ({@link com.meridian.substrate.common.repo.CascadeLevelActionRepository})
 * exposes no update/delete methods, services never mutate loaded rows, and on
 * Postgres the V4_1 trigger raises on any UPDATE/DELETE. H2 dev is
 * service-enforced only (precedent V2_1; H2-green is never acceptance).
 *
 * <p>Payload conditionals (invariant I4, DB CHECK back-stopped):
 * refinedCritique ⟺ ISSUE/CASCADE/ABSORB/RECASCADE; declineReasoning ⟺
 * DECLINE; resolutionNote ⟺ RESOLVE_*; nextOwner ⟺ ISSUE/CASCADE/RECASCADE.
 *
 * <p>All references are soft (no DB FKs, app-enforced).
 */
@Entity
@Table(name = "cascade_level_actions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cla_idem", columnNames = {"cascade_id", "idempotency_key"}),
                @UniqueConstraint(name = "uq_cla_seq", columnNames = {"cascade_id", "chain_seq"})
        })
public class CascadeLevelAction extends BaseEntity {

    /** Soft ref → cascades; no DB FK. */
    @Column(name = "cascade_id", nullable = false)
    private UUID cascadeId;

    /** cascade_levels row acted on (depth 0 for ISSUE/RESOLVE_*). Soft ref; no DB FK. */
    @Column(name = "level_id", nullable = false)
    private UUID levelId;

    @Column(name = "depth", nullable = false)
    private int depth;

    /** The level's round at action time. */
    @Column(name = "round", nullable = false)
    private int round;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private CascadeActionType action;

    /**
     * Soft ref → organization_members; no DB FK. Actor identity comes from
     * PersonaContext headers in Phase 2 (spoofable, accepted per S9); the OIDC
     * principal replaces the source in Phase 5 with no schema change (ADR D11).
     */
    @Column(name = "actor_member_id")
    private UUID actorMemberId;

    @Column(name = "actor_label", nullable = false)
    private String actorLabel;

    /** ISSUE/CASCADE/ABSORB/RECASCADE payload (invariant I4). */
    @Column(name = "refined_critique", columnDefinition = "TEXT")
    private String refinedCritique;

    /** DECLINE payload (invariant I4). Decline is a legitimate act — never an error. */
    @Column(name = "decline_reasoning", columnDefinition = "TEXT")
    private String declineReasoning;

    /** RESOLVE_LOOKS_GOOD / RESOLVE_DECLINED payload (invariant I4). */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    /**
     * ISSUE/CASCADE/RECASCADE routing. Soft ref → organization_members; no DB FK.
     * EXTENSION POINT(cascade-11): routing validation against the org chart is NOT
     * encoded — OrganizationMember.reportsTo is a name string, not an FK; mis-routing
     * is silently legitimate in v1 (named risk, not an invariant — spec §13.11).
     */
    @Column(name = "next_owner_member_id")
    private UUID nextOwnerMemberId;

    @Column(name = "next_owner_label")
    private String nextOwnerLabel;

    /** RESOLVE_DECLINED only. Soft ref → cascade_level_actions; no DB FK. */
    @Column(name = "accepted_decline_action_id")
    private UUID acceptedDeclineActionId;

    /** From the injected Clock (invariant I5). */
    @Column(name = "acted_at", nullable = false)
    private Instant actedAt;

    /** ADR D8: mandatory client-generated idempotency key; UNIQUE(cascade_id, idempotency_key). */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** 1..N per cascade, gapless; UNIQUE(cascade_id, chain_seq) (invariant I5). */
    @Column(name = "chain_seq", nullable = false)
    private int chainSeq;

    // -------------------------------------------------------------- accessors
    // (setters exist for construction only — service discipline + the
    // Repository-not-JpaRepository surface + the PG trigger keep rows append-only)

    public UUID getCascadeId() {
        return cascadeId;
    }

    public void setCascadeId(UUID cascadeId) {
        this.cascadeId = cascadeId;
    }

    public UUID getLevelId() {
        return levelId;
    }

    public void setLevelId(UUID levelId) {
        this.levelId = levelId;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public CascadeActionType getAction() {
        return action;
    }

    public void setAction(CascadeActionType action) {
        this.action = action;
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

    public String getRefinedCritique() {
        return refinedCritique;
    }

    public void setRefinedCritique(String refinedCritique) {
        this.refinedCritique = refinedCritique;
    }

    public String getDeclineReasoning() {
        return declineReasoning;
    }

    public void setDeclineReasoning(String declineReasoning) {
        this.declineReasoning = declineReasoning;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public UUID getNextOwnerMemberId() {
        return nextOwnerMemberId;
    }

    public void setNextOwnerMemberId(UUID nextOwnerMemberId) {
        this.nextOwnerMemberId = nextOwnerMemberId;
    }

    public String getNextOwnerLabel() {
        return nextOwnerLabel;
    }

    public void setNextOwnerLabel(String nextOwnerLabel) {
        this.nextOwnerLabel = nextOwnerLabel;
    }

    public UUID getAcceptedDeclineActionId() {
        return acceptedDeclineActionId;
    }

    public void setAcceptedDeclineActionId(UUID acceptedDeclineActionId) {
        this.acceptedDeclineActionId = acceptedDeclineActionId;
    }

    public Instant getActedAt() {
        return actedAt;
    }

    public void setActedAt(Instant actedAt) {
        this.actedAt = actedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getChainSeq() {
        return chainSeq;
    }

    public void setChainSeq(int chainSeq) {
        this.chainSeq = chainSeq;
    }
}
