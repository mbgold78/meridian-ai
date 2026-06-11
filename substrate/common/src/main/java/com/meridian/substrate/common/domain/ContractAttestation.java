package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Human/agent attestation of a commitment for a period (engine-spec
 * contracts.md §3.2 table 10; ADR-002 D13). Idempotent upsert on
 * {@code UNIQUE(commitment_id, period_key)} — the row carries the LATEST
 * submission; every submission appends a {@code COMMITMENT_ATTESTED}
 * {@code contract_events} row so history is durable (§5.5).
 *
 * <p>{@code evidenceRefs} are soft references to existing
 * Evidence/DocumentRegistry rows (EVIDENCE-GROUNDING shape) — validated for
 * existence, warn-don't-fail if missing.
 */
@Entity
@Table(name = "contract_attestations")
public class ContractAttestation extends BaseEntity {

    /** Soft ref → contract_commitments; no DB FK (app-enforced). */
    @Column(name = "commitment_id", nullable = false)
    private UUID commitmentId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commitment_id", insertable = false, updatable = false)
    private ContractCommitment commitment;

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** Soft ref → contract_parties; no DB FK (app-enforced). */
    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", insertable = false, updatable = false)
    private ContractParty party;

    /** '2026-07' (MONTHLY) / '2026-Q3' (QUARTERLY) / 'ONE_TIME' (§5.1). */
    @Column(name = "period_key", nullable = false, length = 16)
    private String periodKey;

    @Column(name = "attested_by", nullable = false)
    private String attestedBy;

    @Column(name = "attested_at", nullable = false)
    private Instant attestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_claim", nullable = false, length = 16)
    private ComplianceClaim complianceClaim;

    @Column(name = "statement", columnDefinition = "TEXT")
    private String statement;

    /** JSON array of Evidence/DocumentRegistry ids (soft refs). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence_refs", columnDefinition = "TEXT")
    private JsonNode evidenceRefs;

    /** UI | MCP | API. */
    @Column(name = "submitted_via", nullable = false, length = 16)
    private String submittedVia = "API";

    // -------------------------------------------------------------- accessors

    public UUID getCommitmentId() {
        return commitmentId;
    }

    public void setCommitmentId(UUID commitmentId) {
        this.commitmentId = commitmentId;
    }

    public ContractCommitment getCommitment() {
        return commitment;
    }

    public UUID getContractId() {
        return contractId;
    }

    public void setContractId(UUID contractId) {
        this.contractId = contractId;
    }

    public Contract getContract() {
        return contract;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public ContractParty getParty() {
        return party;
    }

    public String getPeriodKey() {
        return periodKey;
    }

    public void setPeriodKey(String periodKey) {
        this.periodKey = periodKey;
    }

    public String getAttestedBy() {
        return attestedBy;
    }

    public void setAttestedBy(String attestedBy) {
        this.attestedBy = attestedBy;
    }

    public Instant getAttestedAt() {
        return attestedAt;
    }

    public void setAttestedAt(Instant attestedAt) {
        this.attestedAt = attestedAt;
    }

    public ComplianceClaim getComplianceClaim() {
        return complianceClaim;
    }

    public void setComplianceClaim(ComplianceClaim complianceClaim) {
        this.complianceClaim = complianceClaim;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public JsonNode getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(JsonNode evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public String getSubmittedVia() {
        return submittedVia;
    }

    public void setSubmittedVia(String submittedVia) {
        this.submittedVia = submittedVia;
    }
}
