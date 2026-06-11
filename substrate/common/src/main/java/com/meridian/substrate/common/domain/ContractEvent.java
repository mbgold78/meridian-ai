package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * APPEND-ONLY contract audit timeline row (engine-spec contracts.md §3.2
 * table 13) — the contract analog of [CASCADE-DURABILITY]. No UPDATE/DELETE
 * code path exists: the repository surface is insert-only by construction
 * ({@link com.meridian.substrate.common.repo.ContractEventRepository}
 * extends {@code Repository}, spec §2.2 invariants + §10.9; the ArchUnit rule
 * banning mutators lands with chunk 3c).
 *
 * <p>Hash-chain mirroring (activation/decline/waiver only, ADR D14) goes
 * through {@code engine.decision DecisionLogService} under its per-domain
 * append lock — contract events NEVER write the chain directly (§10.7).
 */
@Entity
@Table(name = "contract_events")
public class ContractEvent extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** e.g. CONTRACT_ACTIVATED, CONTRACT_DECLINED, BREACH_RECONFIRMED, COMMITMENT_ATTESTED. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Party/commitment/breach/monitor/attestation id as applicable. Soft ref; no DB FK. */
    @Column(name = "ref_id")
    private UUID refId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private ContractActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "payload", columnDefinition = "TEXT")
    private JsonNode payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public UUID getRefId() {
        return refId;
    }

    public void setRefId(UUID refId) {
        this.refId = refId;
    }

    public ContractActorType getActorType() {
        return actorType;
    }

    public void setActorType(ContractActorType actorType) {
        this.actorType = actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
