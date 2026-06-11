package com.meridian.substrate.common.domain;

import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per cascade depth (engine-spec cascade.md §3.2) — a mutable
 * <em>projection</em>; truth lives in the append-only
 * {@link CascadeLevelAction} log and these rows are deterministically
 * rebuildable from it (ADR D1).
 *
 * <p>{@code refinedCritique} / {@code declineReasoning} hold the
 * <em>current-round</em> projection (fixture DTO shape); prior-round facts
 * remain only in the action log. RECASCADE reactivation increments
 * {@code round} and resets the projections (spec §5.1).
 *
 * <p>{@code cascadeId} and {@code ownerMemberId} are soft references — no DB
 * FKs, application-enforced (canonical-model convention, port-map §2.1).
 */
@Entity
@Table(name = "cascade_levels",
        uniqueConstraints = @UniqueConstraint(name = "uq_cascade_levels_depth",
                columnNames = {"cascade_id", "depth"}))
public class CascadeLevel extends BaseEntity {

    /** Soft ref → cascades; no DB FK. */
    @Column(name = "cascade_id", nullable = false)
    private UUID cascadeId;

    /** 0 = originator; contiguous 0..N (invariant I2). */
    @Column(name = "depth", nullable = false)
    private int depth;

    /**
     * Soft ref → organization_members; current-round owner; no DB FK.
     * EXTENSION POINT(cascade-7): owner references are member-or-label only;
     * agent-as-level-owner rides the agent registry later (spec §13.7).
     */
    @Column(name = "owner_member_id")
    private UUID ownerMemberId;

    @Column(name = "owner_label", nullable = false)
    private String ownerLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false, length = 20)
    private CascadeLevelState currentState;

    /** Starts at 1; incremented on RECASCADE reactivation (spec §5.1). */
    @Column(name = "round", nullable = false)
    private int round = 1;

    /** Current-round projection (fixture DTO shape). */
    @Column(name = "refined_critique", columnDefinition = "TEXT")
    private String refinedCritique;

    /** Current-round projection. */
    @Column(name = "decline_reasoning", columnDefinition = "TEXT")
    private String declineReasoning;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "acted_at")
    private Instant actedAt;

    /** NULL when SLA disabled (responseSlaDays = 0, ADR D5). */
    @Column(name = "response_deadline")
    private Instant responseDeadline;

    @Column(name = "overdue", nullable = false)
    private boolean overdue = false;

    // -------------------------------------------------------------- accessors

    public UUID getCascadeId() {
        return cascadeId;
    }

    public void setCascadeId(UUID cascadeId) {
        this.cascadeId = cascadeId;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public UUID getOwnerMemberId() {
        return ownerMemberId;
    }

    public void setOwnerMemberId(UUID ownerMemberId) {
        this.ownerMemberId = ownerMemberId;
    }

    public String getOwnerLabel() {
        return ownerLabel;
    }

    public void setOwnerLabel(String ownerLabel) {
        this.ownerLabel = ownerLabel;
    }

    public CascadeLevelState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(CascadeLevelState currentState) {
        this.currentState = currentState;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
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

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public Instant getActedAt() {
        return actedAt;
    }

    public void setActedAt(Instant actedAt) {
        this.actedAt = actedAt;
    }

    public Instant getResponseDeadline() {
        return responseDeadline;
    }

    public void setResponseDeadline(Instant responseDeadline) {
        this.responseDeadline = responseDeadline;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public void setOverdue(boolean overdue) {
        this.overdue = overdue;
    }
}
