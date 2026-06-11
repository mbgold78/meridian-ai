package com.meridian.substrate.common.domain;

import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Cascade aggregate root (engine-spec cascade.md §3.1; ADR-001 D1).
 *
 * <p>Immutable issuance artifacts ({@code originalCritique},
 * {@code acceptanceCriteria}, origin owner, {@code originatedAt}) are never
 * updated after insert ([CASCADE-DURABILITY], invariant I3). Lifecycle fields
 * marked ⟲ in the spec ({@code currentState}, {@code chainSeq}, frontier
 * fields, {@code attentionSignal}, {@code overdue}, the resolution block) are
 * projections rebuildable from the append-only
 * {@link CascadeLevelAction} log.
 *
 * <p>All cross-entity references on this table are soft references — no DB
 * FKs, application-enforced (canonical-model convention, port-map §2.1).
 */
@Entity
@Table(name = "cascades")
public class Cascade extends BaseEntity {

    // ------------------------------------------------------------------ identity & scope

    /** Engagement org scope (holding-co domain). Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /**
     * ADR D2: optional parallel-cascade group. Grouped siblings receive advisory
     * SIBLING_DECLINE notifications only — zero state coupling.
     * EXTENSION POINT(cascade-6): cross-cascade structural decline settlement is
     * advisory-only; an operator-cited decline_reference link is the designed
     * next step if demanded (spec §13.6).
     */
    @Column(name = "cascade_group_id")
    private UUID cascadeGroupId;

    /**
     * Reissue lineage (soft ref → cascades; no DB FK).
     * EXTENSION POINT(cascade-8): no reopen/rework verb exists after terminal —
     * reissue lineage via this column is the only loop (spec §13.8).
     */
    @Column(name = "predecessor_cascade_id")
    private UUID predecessorCascadeId;

    // ------------------------- immutable issuance artifacts (never updated after insert)

    /**
     * Soft ref → organization_members; no DB FK (app-enforced).
     * EXTENSION POINT(cascade-7): owner references are member-or-label only;
     * agent-as-level-owner rides the agent registry later (spec §13.7).
     */
    @Column(name = "origin_owner_member_id")
    private UUID originOwnerMemberId;

    @Column(name = "origin_owner_label", nullable = false)
    private String originOwnerLabel;

    @Column(name = "originated_at", nullable = false)
    private Instant originatedAt;

    @Column(name = "original_critique", nullable = false, columnDefinition = "TEXT")
    private String originalCritique;

    @Column(name = "acceptance_criteria", nullable = false, columnDefinition = "TEXT")
    private String acceptanceCriteria;

    /**
     * EXTENSION POINT(cascade-4): heading/summary shape (queue #22) — column
     * reserved + nullable, unrendered; the authoring shape determines it (spec §13.4).
     */
    @Column(name = "summary", length = 500)
    private String summary;

    /** Soft ref → domains (a Beacon subsidiary IS a Domain); no DB FK. */
    @Column(name = "affected_domain_id")
    private UUID affectedDomainId;

    /**
     * Soft ref → findings; no DB FK.
     * EXTENSION POINT(cascade-5): authoring-from-finding UX is undesigned —
     * leader authoring is gated on the queue-#24 session (spec §13.5).
     */
    @Column(name = "origin_finding_id")
    private UUID originFindingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "authoring_mode", nullable = false, length = 20)
    private CascadeAuthoringMode authoringMode;

    /** HUMAN | AI_ASSISTIVE only — an issued cascade is always human-promoted (ADR D6). */
    @Enumerated(EnumType.STRING)
    @Column(name = "produced_by", nullable = false, length = 20)
    private ProducedBy producedBy;

    /** Soft ref → cascade_drafts; no DB FK. */
    @Column(name = "draft_id")
    private UUID draftId;

    /** NULL = engagement default applied at issue; 0 = SLA disabled (ADR D5). */
    @Column(name = "response_sla_days")
    private Integer responseSlaDays;

    // ----------------------------------------------- lifecycle projections ⟲ (rebuildable)

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false, length = 30)
    private CascadeState currentState;

    /** Fencing token, +1 per action; equals max(action chain_seq) — invariant I10. */
    @Column(name = "chain_seq", nullable = false)
    private int chainSeq = 0;

    /** Bottom-of-chain depth (spec §5.1). */
    @Column(name = "frontier_depth", nullable = false)
    private int frontierDepth;

    @Enumerated(EnumType.STRING)
    @Column(name = "frontier_state", nullable = false, length = 20)
    private CascadeLevelState frontierState;

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_signal", nullable = false, length = 30)
    private CascadeAttentionSignal attentionSignal;

    /** Overlay flag, never a signal value (spec §2). */
    @Column(name = "overdue", nullable = false)
    private boolean overdue = false;

    // ----------------------- resolution accountability record ⟲ (from RESOLVE_* action)

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "resolved_by_member_id")
    private UUID resolvedByMemberId;

    @Column(name = "resolved_by_label")
    private String resolvedByLabel;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    /** Soft ref → cascade_level_actions; set iff RESOLVED_DECLINED. No DB FK. */
    @Column(name = "accepted_decline_action_id")
    private UUID acceptedDeclineActionId;

    // -------------------------------------------------------------- accessors

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public UUID getCascadeGroupId() {
        return cascadeGroupId;
    }

    public void setCascadeGroupId(UUID cascadeGroupId) {
        this.cascadeGroupId = cascadeGroupId;
    }

    public UUID getPredecessorCascadeId() {
        return predecessorCascadeId;
    }

    public void setPredecessorCascadeId(UUID predecessorCascadeId) {
        this.predecessorCascadeId = predecessorCascadeId;
    }

    public UUID getOriginOwnerMemberId() {
        return originOwnerMemberId;
    }

    public void setOriginOwnerMemberId(UUID originOwnerMemberId) {
        this.originOwnerMemberId = originOwnerMemberId;
    }

    public String getOriginOwnerLabel() {
        return originOwnerLabel;
    }

    public void setOriginOwnerLabel(String originOwnerLabel) {
        this.originOwnerLabel = originOwnerLabel;
    }

    public Instant getOriginatedAt() {
        return originatedAt;
    }

    public void setOriginatedAt(Instant originatedAt) {
        this.originatedAt = originatedAt;
    }

    public String getOriginalCritique() {
        return originalCritique;
    }

    public void setOriginalCritique(String originalCritique) {
        this.originalCritique = originalCritique;
    }

    public String getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public void setAcceptanceCriteria(String acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public UUID getAffectedDomainId() {
        return affectedDomainId;
    }

    public void setAffectedDomainId(UUID affectedDomainId) {
        this.affectedDomainId = affectedDomainId;
    }

    public UUID getOriginFindingId() {
        return originFindingId;
    }

    public void setOriginFindingId(UUID originFindingId) {
        this.originFindingId = originFindingId;
    }

    public CascadeAuthoringMode getAuthoringMode() {
        return authoringMode;
    }

    public void setAuthoringMode(CascadeAuthoringMode authoringMode) {
        this.authoringMode = authoringMode;
    }

    public ProducedBy getProducedBy() {
        return producedBy;
    }

    public void setProducedBy(ProducedBy producedBy) {
        this.producedBy = producedBy;
    }

    public UUID getDraftId() {
        return draftId;
    }

    public void setDraftId(UUID draftId) {
        this.draftId = draftId;
    }

    public Integer getResponseSlaDays() {
        return responseSlaDays;
    }

    public void setResponseSlaDays(Integer responseSlaDays) {
        this.responseSlaDays = responseSlaDays;
    }

    public CascadeState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(CascadeState currentState) {
        this.currentState = currentState;
    }

    public int getChainSeq() {
        return chainSeq;
    }

    public void setChainSeq(int chainSeq) {
        this.chainSeq = chainSeq;
    }

    public int getFrontierDepth() {
        return frontierDepth;
    }

    public void setFrontierDepth(int frontierDepth) {
        this.frontierDepth = frontierDepth;
    }

    public CascadeLevelState getFrontierState() {
        return frontierState;
    }

    public void setFrontierState(CascadeLevelState frontierState) {
        this.frontierState = frontierState;
    }

    public CascadeAttentionSignal getAttentionSignal() {
        return attentionSignal;
    }

    public void setAttentionSignal(CascadeAttentionSignal attentionSignal) {
        this.attentionSignal = attentionSignal;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public void setOverdue(boolean overdue) {
        this.overdue = overdue;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public UUID getResolvedByMemberId() {
        return resolvedByMemberId;
    }

    public void setResolvedByMemberId(UUID resolvedByMemberId) {
        this.resolvedByMemberId = resolvedByMemberId;
    }

    public String getResolvedByLabel() {
        return resolvedByLabel;
    }

    public void setResolvedByLabel(String resolvedByLabel) {
        this.resolvedByLabel = resolvedByLabel;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public UUID getAcceptedDeclineActionId() {
        return acceptedDeclineActionId;
    }

    public void setAcceptedDeclineActionId(UUID acceptedDeclineActionId) {
        this.acceptedDeclineActionId = acceptedDeclineActionId;
    }
}
