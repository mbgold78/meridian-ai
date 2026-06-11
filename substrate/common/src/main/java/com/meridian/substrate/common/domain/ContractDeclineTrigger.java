package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A pre-agreed legitimate refusal ground (engine-spec contracts.md §3.2
 * table 5; [CONTRACT-COMMITMENT-STATES]). Invoking one is VALID contract
 * behavior — rendered with subtle destructive accent, NEVER a violation.
 *
 * <p>Standing is DERIVED, not a mutable state column (§2.6): for
 * predicate-bearing triggers the sweep evaluates {@code predicate} (tiny DSL,
 * §4.4) and sets the denormalized {@code armed}/{@code lastArmedAt}.
 * Predicate-null triggers are pure human-judgment grounds, always invocable,
 * never armed.
 *
 * <p>EXTENSION POINT(contracts-6, §15 #6 / ADR D9): temporal predicates
 * ("sustained over two quarters" — the fi-shared fixture trigger) exceed the
 * v1 DSL and ship predicate-null; richer logic is the {@code GuardPredicate}
 * SPI, not ad-hoc DSL growth.
 */
@Entity
@Table(name = "contract_decline_triggers")
public class ContractDeclineTrigger extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** VERBATIM fixture trigger string. */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /** Nullable tiny-DSL JSON (§4.4); null = human judgment, always invocable. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "predicate", columnDefinition = "TEXT")
    private JsonNode predicate;

    /** Soft ref → contract_parties; null = any party may invoke. */
    @Column(name = "invocable_by_party_id")
    private UUID invocableByPartyId;

    /** Denormalized DORMANT/ARMED standing (§2.6); set by the sweep only. */
    @Column(name = "armed", nullable = false)
    private boolean armed = false;

    @Column(name = "last_armed_at")
    private Instant lastArmedAt;

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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public JsonNode getPredicate() {
        return predicate;
    }

    public void setPredicate(JsonNode predicate) {
        this.predicate = predicate;
    }

    public UUID getInvocableByPartyId() {
        return invocableByPartyId;
    }

    public void setInvocableByPartyId(UUID invocableByPartyId) {
        this.invocableByPartyId = invocableByPartyId;
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    public Instant getLastArmedAt() {
        return lastArmedAt;
    }

    public void setLastArmedAt(Instant lastArmedAt) {
        this.lastArmedAt = lastArmedAt;
    }

    public int getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(int sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }
}
