package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * A contract party (engine-spec contracts.md §3.2 table 2;
 * [CONTRACT-CONTENT]: 2..N parties, each with partyLabel + commitments; a
 * party may be a COMBINED obligation across multiple subsidiaries).
 *
 * <p>Frozen with the rest of the contract content at the first party response
 * (ADR D7). {@code boundEntityIds} is a JSON array of
 * {@code {entityType, entityId, slug?}} soft refs — no DB FKs,
 * application-enforced (severs fixture seam S5 slug links).
 */
@Entity
@Table(name = "contract_parties")
public class ContractParty extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** Fixture string, e.g. 'Sentari, Tellix, Bursar (combined obligation)'. */
    @Column(name = "party_label", nullable = false)
    private String partyLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false, length = 32)
    private ContractPartyType partyType;

    /** JSON array of {entityType, entityId, slug?} soft refs. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "bound_entity_ids", columnDefinition = "TEXT")
    private JsonNode boundEntityIds;

    /**
     * Set when the party's agent/system binds via MCP (P19). Pre-Phase-5 this
     * is advisory — spoofable header identity, recorded verbatim (spec §8.1,
     * §15 extension point #12).
     */
    @Column(name = "mcp_principal_id")
    private String mcpPrincipalId;

    @Column(name = "response_required", nullable = false)
    private boolean responseRequired = true;

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

    public String getPartyLabel() {
        return partyLabel;
    }

    public void setPartyLabel(String partyLabel) {
        this.partyLabel = partyLabel;
    }

    public ContractPartyType getPartyType() {
        return partyType;
    }

    public void setPartyType(ContractPartyType partyType) {
        this.partyType = partyType;
    }

    public JsonNode getBoundEntityIds() {
        return boundEntityIds;
    }

    public void setBoundEntityIds(JsonNode boundEntityIds) {
        this.boundEntityIds = boundEntityIds;
    }

    public String getMcpPrincipalId() {
        return mcpPrincipalId;
    }

    public void setMcpPrincipalId(String mcpPrincipalId) {
        this.mcpPrincipalId = mcpPrincipalId;
    }

    public boolean isResponseRequired() {
        return responseRequired;
    }

    public void setResponseRequired(boolean responseRequired) {
        this.responseRequired = responseRequired;
    }

    public int getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(int sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }
}
