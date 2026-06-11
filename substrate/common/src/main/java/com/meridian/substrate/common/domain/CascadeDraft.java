package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Mutable cascade authoring persistence, OUTSIDE the audit trail (engine-spec
 * cascade.md §3.4; ADR D6 — a draft demands nothing of anyone; only human
 * promotion via {@code /issue} creates a cascade, born IN_FLIGHT).
 *
 * <p>AI-suggested text lands in the {@code suggested*} columns with full
 * orchestrator dispatch provenance; {@code suggestionState} is AI-assist
 * plumbing status only, never governance state.
 *
 * <p>All cross-entity references are soft (no DB FKs, app-enforced).
 */
@Entity
@Table(name = "cascade_drafts")
public class CascadeDraft extends BaseEntity {

    /** Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "author_member_id")
    private UUID authorMemberId;

    @Column(name = "author_label", nullable = false)
    private String authorLabel;

    @Column(name = "original_critique", columnDefinition = "TEXT")
    private String originalCritique;

    @Column(name = "acceptance_criteria", columnDefinition = "TEXT")
    private String acceptanceCriteria;

    /**
     * EXTENSION POINT(cascade-4): heading/summary shape (queue #22) — reserved
     * + nullable, unrendered; the authoring shape determines it (spec §13.4).
     */
    @Column(name = "summary", length = 500)
    private String summary;

    /** Soft ref → domains; no DB FK. */
    @Column(name = "affected_domain_id")
    private UUID affectedDomainId;

    /**
     * Soft ref → findings; no DB FK.
     * EXTENSION POINT(cascade-5): authoring-from-finding UX is undesigned —
     * leader authoring is gated on the queue-#24 session (spec §13.5).
     */
    @Column(name = "origin_finding_id")
    private UUID originFindingId;

    /** ADR D2: optional parallel-cascade group. Soft ref; no DB FK. */
    @Column(name = "cascade_group_id")
    private UUID cascadeGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "authoring_mode", nullable = false, length = 20)
    private CascadeAuthoringMode authoringMode;

    /** NULL = engagement default applied at issue; 0 = SLA disabled (ADR D5). */
    @Column(name = "response_sla_days")
    private Integer responseSlaDays;

    @Column(name = "suggested_critique", columnDefinition = "TEXT")
    private String suggestedCritique;

    @Column(name = "suggested_acceptance_criteria", columnDefinition = "TEXT")
    private String suggestedAcceptanceCriteria;

    /**
     * Orchestrator dispatch provenance JSON:
     * {@code {taskId, model, eventsDigest, requestedAt, landedAt}}
     * (ProducedBy=AI_GENERATIVE appears ONLY inside this provenance — ADR D6).
     */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "suggestion_provenance", columnDefinition = "TEXT")
    private JsonNode suggestionProvenance;

    /** AI-assist plumbing status only — never governance state (spec §2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "suggestion_state", nullable = false, length = 20)
    private CascadeSuggestionState suggestionState = CascadeSuggestionState.NONE;

    @Column(name = "orchestrator_task_id")
    private String orchestratorTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CascadeDraftStatus status = CascadeDraftStatus.OPEN;

    /** Soft ref → cascades; set when the draft is ISSUED. No DB FK. */
    @Column(name = "issued_cascade_id")
    private UUID issuedCascadeId;

    // -------------------------------------------------------------- accessors

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public UUID getAuthorMemberId() {
        return authorMemberId;
    }

    public void setAuthorMemberId(UUID authorMemberId) {
        this.authorMemberId = authorMemberId;
    }

    public String getAuthorLabel() {
        return authorLabel;
    }

    public void setAuthorLabel(String authorLabel) {
        this.authorLabel = authorLabel;
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

    public UUID getCascadeGroupId() {
        return cascadeGroupId;
    }

    public void setCascadeGroupId(UUID cascadeGroupId) {
        this.cascadeGroupId = cascadeGroupId;
    }

    public CascadeAuthoringMode getAuthoringMode() {
        return authoringMode;
    }

    public void setAuthoringMode(CascadeAuthoringMode authoringMode) {
        this.authoringMode = authoringMode;
    }

    public Integer getResponseSlaDays() {
        return responseSlaDays;
    }

    public void setResponseSlaDays(Integer responseSlaDays) {
        this.responseSlaDays = responseSlaDays;
    }

    public String getSuggestedCritique() {
        return suggestedCritique;
    }

    public void setSuggestedCritique(String suggestedCritique) {
        this.suggestedCritique = suggestedCritique;
    }

    public String getSuggestedAcceptanceCriteria() {
        return suggestedAcceptanceCriteria;
    }

    public void setSuggestedAcceptanceCriteria(String suggestedAcceptanceCriteria) {
        this.suggestedAcceptanceCriteria = suggestedAcceptanceCriteria;
    }

    public JsonNode getSuggestionProvenance() {
        return suggestionProvenance;
    }

    public void setSuggestionProvenance(JsonNode suggestionProvenance) {
        this.suggestionProvenance = suggestionProvenance;
    }

    public CascadeSuggestionState getSuggestionState() {
        return suggestionState;
    }

    public void setSuggestionState(CascadeSuggestionState suggestionState) {
        this.suggestionState = suggestionState;
    }

    public String getOrchestratorTaskId() {
        return orchestratorTaskId;
    }

    public void setOrchestratorTaskId(String orchestratorTaskId) {
        this.orchestratorTaskId = orchestratorTaskId;
    }

    public CascadeDraftStatus getStatus() {
        return status;
    }

    public void setStatus(CascadeDraftStatus status) {
        this.status = status;
    }

    public UUID getIssuedCascadeId() {
        return issuedCascadeId;
    }

    public void setIssuedCascadeId(UUID issuedCascadeId) {
        this.issuedCascadeId = issuedCascadeId;
    }
}
