package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A party's concrete, dated, measurable obligation (engine-spec contracts.md
 * §3.2 table 4; [CONTRACT-CONTENT]). {@code text} is the VERBATIM fixture
 * commitment string.
 *
 * <p>{@code complianceState} is a denormalized projection (§2.3) driven ONLY
 * by monitor runs, attestation claims and breach transitions — never directly
 * editable via API; history lives in {@code contract_events}. Evaluable only
 * while the contract is ACTIVE.
 */
@Entity
@Table(name = "contract_commitments")
public class ContractCommitment extends BaseEntity {

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

    /** VERBATIM fixture commitment string. */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "due_by")
    private Instant dueBy;

    /** MONTHLY | QUARTERLY | … nullable (free-text precedent; no enum in spec §2.1). */
    @Column(name = "cadence", length = 16)
    private String cadence;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_state", nullable = false, length = 32)
    private CommitmentComplianceState complianceState = CommitmentComplianceState.UNEVALUATED;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

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

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public ContractParty getParty() {
        return party;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Instant getDueBy() {
        return dueBy;
    }

    public void setDueBy(Instant dueBy) {
        this.dueBy = dueBy;
    }

    public String getCadence() {
        return cadence;
    }

    public void setCadence(String cadence) {
        this.cadence = cadence;
    }

    public CommitmentComplianceState getComplianceState() {
        return complianceState;
    }

    public void setComplianceState(CommitmentComplianceState complianceState) {
        this.complianceState = complianceState;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void setLastEvaluatedAt(Instant lastEvaluatedAt) {
        this.lastEvaluatedAt = lastEvaluatedAt;
    }

    public int getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(int sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }
}
