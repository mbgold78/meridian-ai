package com.meridian.substrate.engine.contract.gateway;

/**
 * An AGENT-principal governed action was denied by the agent registry gate
 * in enforce mode (engine-spec agents.md §8.5; ADR-003 D7). The wire shapes
 * reuse ADR-002 §4.5 exactly with the additive code
 * {@value #AGENT_GATE_CODE}: REST — HTTP 409 envelope, message citing the
 * reason code + agent key + decisionId; MCP — {@code isError=true} with
 * structured {@code {code:"AGENT_GATE_DENIAL", reason, decisionId, …}} so
 * runtimes adapt rather than crash. Never a 5xx.
 *
 * <p>Subtypes {@link ContractDeniedException} deliberately: the enforce-mode
 * rollback path, the REQUIRES_NEW decision-row survival (§10.4) and every
 * adapter's denial translation are shared — only the {@link #code()}
 * discriminator differs.
 */
public class AgentGateDeniedException extends ContractDeniedException {

    /** §8.5 envelope/MCP {@code code} value. */
    public static final String AGENT_GATE_CODE = "AGENT_GATE_DENIAL";

    public AgentGateDeniedException(Decision.Deny deny, String humanSummary) {
        super(deny, humanSummary);
    }

    @Override
    public String code() {
        return AGENT_GATE_CODE;
    }
}
