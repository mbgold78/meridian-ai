package com.meridian.substrate.engine.contract.exception;

import com.meridian.substrate.common.domain.enums.ContractActorType;

import java.util.UUID;

/**
 * Breach waiver attempted by a non-HUMAN actor (engine-spec contracts.md
 * §5.4/§7.2; ADR-002 D16: "agents may attest, acknowledge, and propose
 * remediation; they may never waive"). This is the service-level half of the
 * built-in non-removable guard — the 3c gateway PEP denies the
 * {@code waive_breach} MCP tool for AGENT principals; this exception backstops
 * the REST path ("principal check in the service", §5.4). App wire mapping:
 * 403 (§7.2 "human-only (agents 403/denied)").
 */
public class WaiverHumanOnlyException extends RuntimeException {

    private final UUID breachId;
    private final ContractActorType actorType;

    public WaiverHumanOnlyException(UUID breachId, ContractActorType actorType) {
        super("breach " + breachId + " waiver denied for actor type " + actorType
                + " — waiver is human-only (ADR D16, built-in non-removable guard)");
        this.breachId = breachId;
        this.actorType = actorType;
    }

    public UUID getBreachId() {
        return breachId;
    }

    public ContractActorType getActorType() {
        return actorType;
    }
}
