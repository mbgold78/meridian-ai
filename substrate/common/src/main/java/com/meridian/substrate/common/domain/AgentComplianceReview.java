package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Promotion-gate currency (engine-spec agents.md §4 table 5; ADR-003 D10).
 * Records reviewer identity, decision, rationale, evidence refs and NOTHING
 * else — review-content semantics ("what HIPAA validation requires") are
 * UNKNOWN and must not be quietly encoded (flag-not-invent, spec §17 #3).
 *
 * <p>Single-use: {@code consumedByEventId} is set when a PROMOTED event
 * consumes this review (M4). REJECTED renders info/neutral — never an error
 * (invariant I5); service rule: rationale NOT NULL at decision time.
 */
@Entity
@Table(name = "agent_compliance_reviews")
public class AgentComplianceReview extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_kind", nullable = false, length = 20)
    private AgentReviewKind reviewKind;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "requested_by_label", nullable = false)
    private String requestedByLabel;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "reviewer_member_id")
    private UUID reviewerMemberId;

    @Column(name = "reviewer_label")
    private String reviewerLabel;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 20)
    private AgentReviewDecision decision;

    /** Service rule: NOT NULL at decision. */
    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    /** JSON array of Evidence/DocumentRegistry ids (soft refs). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence_refs", columnDefinition = "TEXT")
    private JsonNode evidenceRefs;

    /** Single-use marker: the PROMOTED event that consumed this review (soft ref). */
    @Column(name = "consumed_by_event_id")
    private UUID consumedByEventId;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public AgentReviewKind getReviewKind() {
        return reviewKind;
    }

    public void setReviewKind(AgentReviewKind reviewKind) {
        this.reviewKind = reviewKind;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public String getRequestedByLabel() {
        return requestedByLabel;
    }

    public void setRequestedByLabel(String requestedByLabel) {
        this.requestedByLabel = requestedByLabel;
    }

    public UUID getReviewerMemberId() {
        return reviewerMemberId;
    }

    public void setReviewerMemberId(UUID reviewerMemberId) {
        this.reviewerMemberId = reviewerMemberId;
    }

    public String getReviewerLabel() {
        return reviewerLabel;
    }

    public void setReviewerLabel(String reviewerLabel) {
        this.reviewerLabel = reviewerLabel;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public AgentReviewDecision getDecision() {
        return decision;
    }

    public void setDecision(AgentReviewDecision decision) {
        this.decision = decision;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public JsonNode getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(JsonNode evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public UUID getConsumedByEventId() {
        return consumedByEventId;
    }

    public void setConsumedByEventId(UUID consumedByEventId) {
        this.consumedByEventId = consumedByEventId;
    }
}
