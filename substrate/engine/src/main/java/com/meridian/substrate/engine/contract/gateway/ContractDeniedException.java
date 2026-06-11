package com.meridian.substrate.engine.contract.gateway;

import java.util.UUID;

/**
 * A governed action was DENIED in enforce mode (engine-spec contracts.md
 * §4.3/§4.5; ADR D1/D4). Thrown from inside the business transaction so the
 * mutation rolls back; the DENY decision row was already persisted in
 * REQUIRES_NEW and survives (§10.4, ADR D17).
 *
 * <p>Wire shapes (§4.5): REST — HTTP <b>409</b>, envelope code
 * {@code CONTRACT_DENIAL}, message = this exception's human summary (citing
 * contract key + clause descriptions + decisionId); never a 5xx. MCP —
 * {@code isError=true} with structured content
 * {@code {code, contractKeys, clauseIds, reason, decisionId}} from
 * {@link #getDeny()} so agents adapt rather than crash.
 *
 * <p>NEVER used for decline: DECLINE is a legitimate first-class governance
 * outcome that returns 200 (port-map trap #8; ADR D6). A denial is a
 * governance outcome too — error-shaped on the wire only because the action
 * must not proceed.
 */
public class ContractDeniedException extends RuntimeException {

    /** Envelope/MCP {@code code} value (§4.5). */
    public static final String CODE = "CONTRACT_DENIAL";

    private final transient Decision.Deny deny;

    public ContractDeniedException(Decision.Deny deny, String humanSummary) {
        super(humanSummary);
        this.deny = deny;
    }

    public ContractDeniedException(Decision.Deny deny, String humanSummary, Throwable cause) {
        super(humanSummary, cause);
        this.deny = deny;
    }

    /**
     * The envelope/MCP {@code code} discriminator (§4.5):
     * {@value #CODE} here; {@code AGENT_GATE_DENIAL} on the
     * {@link AgentGateDeniedException} subtype (agents.md §8.5) — adapters
     * translate via this method, never by instanceof chains.
     */
    public String code() {
        return CODE;
    }

    public Decision.Deny getDeny() {
        return deny;
    }

    public UUID getDecisionId() {
        return deny.decisionId();
    }
}
