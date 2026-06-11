package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.AgentInterventionResolution;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Attention-overlay row (engine-spec agents.md §4 table 4; ADR-003 D4): the
 * agent KEEPS its {@code operational_mode} while an intervention is open —
 * an in-intervention agent is typically STILL RUNNING (verified fixture
 * truth: Curasync No-Show Predictor); pausing it is a separate evented act.
 *
 * <p>Engine-raised rows dedupe on (agent, source_key): at most one OPEN row
 * (service + V6_1 partial unique — invariant I12). Service rules: resolution
 * + resolutionNote NOT NULL iff RESOLVED. Intervention verbs return HTTP 200
 * and are never error-colored (invariant I5).
 */
@Entity
@Table(name = "agent_interventions")
public class AgentIntervention extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    /** Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AgentInterventionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 30)
    private AgentInterventionTrigger triggerKind;

    /** Dedupe key for engine-raised rows (e.g. 'active-while-paused'); NULL for human rows. */
    @Column(name = "source_key", length = 160)
    private String sourceKey;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** JSON array of Evidence/DocumentRegistry ids (soft refs). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence_refs", columnDefinition = "TEXT")
    private JsonNode evidenceRefs;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "opened_by_member_id")
    private UUID openedByMemberId;

    @Column(name = "opened_by_label", nullable = false)
    private String openedByLabel;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "acknowledged_by_label")
    private String acknowledgedByLabel;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_by_label")
    private String resolvedByLabel;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Service rule: NOT NULL iff RESOLVED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 30)
    private AgentInterventionResolution resolution;

    /** Service rule: NOT NULL iff RESOLVED. */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

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

    public AgentInterventionStatus getStatus() {
        return status;
    }

    public void setStatus(AgentInterventionStatus status) {
        this.status = status;
    }

    public AgentInterventionTrigger getTriggerKind() {
        return triggerKind;
    }

    public void setTriggerKind(AgentInterventionTrigger triggerKind) {
        this.triggerKind = triggerKind;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public JsonNode getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(JsonNode evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public UUID getOpenedByMemberId() {
        return openedByMemberId;
    }

    public void setOpenedByMemberId(UUID openedByMemberId) {
        this.openedByMemberId = openedByMemberId;
    }

    public String getOpenedByLabel() {
        return openedByLabel;
    }

    public void setOpenedByLabel(String openedByLabel) {
        this.openedByLabel = openedByLabel;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public String getAcknowledgedByLabel() {
        return acknowledgedByLabel;
    }

    public void setAcknowledgedByLabel(String acknowledgedByLabel) {
        this.acknowledgedByLabel = acknowledgedByLabel;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getResolvedByLabel() {
        return resolvedByLabel;
    }

    public void setResolvedByLabel(String resolvedByLabel) {
        this.resolvedByLabel = resolvedByLabel;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public AgentInterventionResolution getResolution() {
        return resolution;
    }

    public void setResolution(AgentInterventionResolution resolution) {
        this.resolution = resolution;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }
}
