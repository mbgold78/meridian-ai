package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Enforcement clause guard — the PDP hot path (engine-spec contracts.md §3.2
 * table 7; ADR-002 D9): FIRST-CLASS COLUMNS ({@code actionType},
 * {@code entityType}, {@code effect}, {@code active}), no JSON in the match
 * path. The optional {@code predicate} (tiny DSL §4.4) supports field
 * equality and numeric thresholds ONLY.
 *
 * <p>{@code active} is true only while the contract is ACTIVE — flipped
 * atomically on activation/decline (§2.2) with guard-cache invalidation.
 */
@Entity
@Table(name = "contract_clause_guards")
public class ContractClauseGuard extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** Soft ref → contract_commitments; nullable (REQUIRE_ATTESTATION binds here). */
    @Column(name = "commitment_id")
    private UUID commitmentId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commitment_id", insertable = false, updatable = false)
    private ContractCommitment commitment;

    /** e.g. 'agent.promote', 'apply_dispatch_result'. */
    @Column(name = "action_type", nullable = false)
    private String actionType;

    /** Nullable narrowing; null = wildcard (§4.3). */
    @Column(name = "entity_type", length = 64)
    private String entityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 32)
    private GuardEffect effect;

    /** Nullable tiny-DSL JSON (§4.4); null = always matches. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "predicate", columnDefinition = "TEXT")
    private JsonNode predicate;

    /** True only while the contract is ACTIVE. */
    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

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

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public GuardEffect getEffect() {
        return effect;
    }

    public void setEffect(GuardEffect effect) {
        this.effect = effect;
    }

    public JsonNode getPredicate() {
        return predicate;
    }

    public void setPredicate(JsonNode predicate) {
        this.predicate = predicate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
