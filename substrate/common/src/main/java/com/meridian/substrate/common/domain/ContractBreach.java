package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachResolution;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.Severity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A detected contract breach (engine-spec contracts.md §3.2 table 12, §2.4).
 * Lifecycle is monotonic forward-only
 * ({@code DETECTED → SURFACED → ACKNOWLEDGED → REMEDIATED | WAIVED});
 * rows are never deleted; every transition appends a {@code contract_events}
 * row with actor identity.
 *
 * <p>Dedup invariant (ADR D13): at most one OPEN breach per
 * {@code (monitor_id, commitment_id, period_key)} — re-detection refreshes
 * {@code lastConfirmedAt} and appends {@code BREACH_RECONFIRMED}, never a
 * second row. Service-enforced under row lock; PG partial unique index
 * backstop in V5_1 (H2 dev service-enforced only).
 *
 * <p>Breach ≠ decline, structurally: open breaches never block decline, they
 * survive it and run their human lifecycle to terminal (§6.3; §15 extension
 * point #5). WAIVED is human-only with mandatory rationale (ADR D16), mirrored
 * to the hash chain (ADR D14).
 */
@Entity
@Table(name = "contract_breaches")
public class ContractBreach extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** Soft ref → contract_commitments; nullable. */
    @Column(name = "commitment_id")
    private UUID commitmentId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commitment_id", insertable = false, updatable = false)
    private ContractCommitment commitment;

    /** Soft ref → contract_monitors; nullable (HUMAN_ATTESTATION breaches have none). */
    @Column(name = "monitor_id")
    private UUID monitorId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitor_id", insertable = false, updatable = false)
    private ContractMonitor monitor;

    @Column(name = "period_key", length = 16)
    private String periodKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BreachStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private Severity severity;

    @Column(name = "summary", nullable = false, length = 512)
    private String summary;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_by", nullable = false, length = 32)
    private BreachDetectedBy detectedBy;

    /** Refreshed on re-detection (ADR D13). */
    @Column(name = "last_confirmed_at")
    private Instant lastConfirmedAt;

    @Column(name = "surfaced_at")
    private Instant surfacedAt;

    /** Named human actor (§2.4). */
    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 16)
    private BreachResolution resolution;

    @Column(name = "resolution_actor")
    private String resolutionActor;

    /** Mandatory for WAIVED (service-enforced). */
    @Column(name = "resolution_rationale", columnDefinition = "TEXT")
    private String resolutionRationale;

    /** JSON; mandatory for REMEDIATED (service-enforced). Soft Evidence/DocumentRegistry refs. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "remediation_evidence_refs", columnDefinition = "TEXT")
    private JsonNode remediationEvidenceRefs;

    // -------------------------------------------------------------- accessors

    public UUID getContractId() {
        return contractId;
    }

    public void setContractId(UUID contractId) {
        this.contractId = contractId;
    }

    public Contract getContract() {
        return contract;
    }

    public UUID getCommitmentId() {
        return commitmentId;
    }

    public void setCommitmentId(UUID commitmentId) {
        this.commitmentId = commitmentId;
    }

    public ContractCommitment getCommitment() {
        return commitment;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    public void setMonitorId(UUID monitorId) {
        this.monitorId = monitorId;
    }

    public ContractMonitor getMonitor() {
        return monitor;
    }

    public String getPeriodKey() {
        return periodKey;
    }

    public void setPeriodKey(String periodKey) {
        this.periodKey = periodKey;
    }

    public BreachStatus getStatus() {
        return status;
    }

    public void setStatus(BreachStatus status) {
        this.status = status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public BreachDetectedBy getDetectedBy() {
        return detectedBy;
    }

    public void setDetectedBy(BreachDetectedBy detectedBy) {
        this.detectedBy = detectedBy;
    }

    public Instant getLastConfirmedAt() {
        return lastConfirmedAt;
    }

    public void setLastConfirmedAt(Instant lastConfirmedAt) {
        this.lastConfirmedAt = lastConfirmedAt;
    }

    public Instant getSurfacedAt() {
        return surfacedAt;
    }

    public void setSurfacedAt(Instant surfacedAt) {
        this.surfacedAt = surfacedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public BreachResolution getResolution() {
        return resolution;
    }

    public void setResolution(BreachResolution resolution) {
        this.resolution = resolution;
    }

    public String getResolutionActor() {
        return resolutionActor;
    }

    public void setResolutionActor(String resolutionActor) {
        this.resolutionActor = resolutionActor;
    }

    public String getResolutionRationale() {
        return resolutionRationale;
    }

    public void setResolutionRationale(String resolutionRationale) {
        this.resolutionRationale = resolutionRationale;
    }

    public JsonNode getRemediationEvidenceRefs() {
        return remediationEvidenceRefs;
    }

    public void setRemediationEvidenceRefs(JsonNode remediationEvidenceRefs) {
        this.remediationEvidenceRefs = remediationEvidenceRefs;
    }
}
