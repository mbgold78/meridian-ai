package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A party's recorded ACCEPTED/DECLINED response (engine-spec contracts.md
 * §3.2 table 3). IMMUTABLE once written — one per party
 * ({@code UNIQUE(party_id)}); duplicate identical response is an idempotent
 * 200, conflicting is 409 {@code RESPONSE_CONFLICT} (spec §10.2).
 *
 * <p>The repository surface is insert-only by construction
 * ({@link com.meridian.substrate.common.repo.ContractPartyResponseRepository}
 * extends {@code Repository}, spec §10.9). {@code declineReasoning} is
 * service-enforced NOT NULL iff {@code response=DECLINED}.
 */
@Entity
@Table(name = "contract_party_responses")
public class ContractPartyResponse extends BaseEntity {

    /** Soft ref → contract_parties; no DB FK (app-enforced). */
    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", insertable = false, updatable = false)
    private ContractParty party;

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @Enumerated(EnumType.STRING)
    @Column(name = "response", nullable = false, length = 16)
    private PartyResponse response;

    @Column(name = "responded_by", nullable = false)
    private String respondedBy;

    @Column(name = "responded_at", nullable = false)
    private Instant respondedAt;

    /** Optional trigger citation at proposal stage. Soft ref → contract_decline_triggers. */
    @Column(name = "decline_trigger_id")
    private UUID declineTriggerId;

    /** Service-enforced NOT NULL iff response=DECLINED. */
    @Column(name = "decline_reasoning", columnDefinition = "TEXT")
    private String declineReasoning;

    // -------------------------------------------------------------- accessors

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public ContractParty getParty() {
        return party;
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

    public PartyResponse getResponse() {
        return response;
    }

    public void setResponse(PartyResponse response) {
        this.response = response;
    }

    public String getRespondedBy() {
        return respondedBy;
    }

    public void setRespondedBy(String respondedBy) {
        this.respondedBy = respondedBy;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }

    public UUID getDeclineTriggerId() {
        return declineTriggerId;
    }

    public void setDeclineTriggerId(UUID declineTriggerId) {
        this.declineTriggerId = declineTriggerId;
    }

    public String getDeclineReasoning() {
        return declineReasoning;
    }

    public void setDeclineReasoning(String declineReasoning) {
        this.declineReasoning = declineReasoning;
    }
}
