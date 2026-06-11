package com.meridian.substrate.engine.contract.gateway;

import java.util.List;

/**
 * Agent-registry gating SPI (engine-spec agents.md §8.1; ADR-003 D7) — the
 * "agent registry gating" extension point contracts.md §15 reserved.
 * {@link ContractGateway} invokes this gate for every
 * {@code PrincipalType.AGENT} action BEFORE clause-guard evaluation; the
 * gate's posture is built-in and non-removable (the {@code waive_breach}
 * D16 idiom).
 *
 * <p><b>Seam contract (whole-or-in-part by construction):</b> this interface
 * and {@link NoopAgentRegistryGate} (always allow-unflagged) belong to the
 * contracts gateway; {@code engine.agent} contributes
 * {@code AgentRegistryGateImpl} as the {@code @Primary} bean. Contracts
 * without the agents module ⇒ the no-op gate; agents without the contracts
 * module ⇒ no gateway exists and every verb is RECORDED_INTENT with the
 * disclosed degraded posture (ADR-003 D17).
 *
 * <p><i>Coordination note:</i> the spec routed this declaration through
 * contracts chunk 3c; 3c landed without it, so phase 6c lands the seam here
 * per the agents.md §8.1 coordinate-at-dispatch rule — same package, same
 * ownership, no fork.
 *
 * <p><b>Fail posture:</b> a gate that THROWS is handled by the gateway's
 * ADR-002 D5 lane — AGENT principals fail closed (deny path, reason
 * {@code GATEWAY_ERROR}); shadow still proceeds (shadow never blocks).
 *
 * <p>Denial wire shape (§8.5): the gateway rows the decision into
 * {@code contract_enforcement_decisions} (no parallel log) and, in enforce
 * mode, raises {@link AgentGateDeniedException} — REST 409 envelope code
 * {@code AGENT_GATE_DENIAL}, MCP structured {@code isError} payload. Never
 * a 5xx.
 */
public interface AgentRegistryGate {

    // §8.3 / §8.2 decision reason vocabulary (rowed verbatim on decisions).
    /** §8.3 check 1 — PAUSED/RETIRED registration touched a governed action. */
    String REASON_CONTROL_SUSPENDED = "AGENT_CONTROL_SUSPENDED";
    /** §8.3 check 2 — SHADOW agents hold observation scope only (P11, structural). */
    String REASON_SHADOW_SCOPE = "AGENT_SHADOW_SCOPE";
    /** §8.3 check 3 — {@code read_only=true} denies mutating actions beyond observation scope. */
    String REASON_READ_ONLY = "AGENT_READ_ONLY";
    /** §8.3 check 4 — active ACTIONS_PER_WINDOW budget exhausted (DB-backed counters, D12). */
    String REASON_BUDGET_EXHAUSTED = "AGENT_BUDGET_EXHAUSTED";
    /** §8.2 step 3 — unregistered principal on a governed write ("unknown agent knocking"). */
    String REASON_UNREGISTERED = "AGENT_UNREGISTERED";
    /** §8.3 check 5 — LIVE agent with an open intervention proceeds FLAGGED, never bricked. */
    String REASON_IN_INTERVENTION = "AGENT_IN_INTERVENTION";

    /**
     * Whether this gate actually consults the registry — {@code true} only
     * for the real {@code engine.agent} implementation. The agent engine's
     * honest control-effect derivation (agents.md §5.3; ADR-003 D6/D17) keys
     * GATEWAY_BLOCK claims off this fact: claiming gateway enforcement while
     * the no-op gate is primary would be fabricated control (invariant I8).
     */
    boolean enforcesRegistry();

    /**
     * The honest enforcement posture of the registry gate RIGHT NOW —
     * bean-presence fact (ADR-003 D17) <b>composed with</b> the single shared
     * enforcement ladder {@code substrate.contracts.enforcement.mode}
     * (ADR-003 D7). The §5.3 control-effect derivation keys GATEWAY_BLOCK
     * off {@link Posture#BLOCKING} ONLY: in {@code shadow} the gateway rows
     * WOULD_DENY and proceeds, and in {@code off} {@link ContractGateway}
     * short-circuits before this gate runs — claiming "the next governed
     * call is denied" in either mode would be fabricated control (spec
     * non-negotiable #1 / invariant I8; the system never claims to have
     * stopped what it does not stop).
     *
     * <p>The default maps the bean-presence fact alone (real gate ⇒
     * BLOCKING) for SPI compatibility; the real implementation overrides
     * mode-aware.
     */
    default Posture posture() {
        return enforcesRegistry() ? Posture.BLOCKING : Posture.ABSENT;
    }

    /** Registry-gate enforcement posture for the §5.3 honest-control derivation. */
    enum Posture {
        /** No real gate bean (contracts module absent) — the D17 degraded window. */
        ABSENT,
        /** Real gate present but the shared ladder is {@code off} — gateway evaluates nothing. */
        INERT,
        /** Real gate present, ladder {@code shadow} — violations rowed WOULD_DENY + signaled, never blocked. */
        OBSERVE,
        /** Real gate present, ladder {@code enforce} — the registry flip IS a hard gateway block. */
        BLOCKING
    }

    /**
     * Evaluates one AGENT-principal governed action (§8.2 resolution order:
     * registered → contract-party carve-out → unregistered policy).
     * {@code mode} is supplied because the unregistered policy is
     * mode-asymmetric BY SPEC (§8.2: shadow ⇒ {@code ALLOW_WITH_FLAG};
     * enforce ⇒ DENY) — the suspended/scope/budget denials stay
     * mode-agnostic {@link Verdict.Deny} and ride the gateway's normal
     * shadow-{@code WOULD_DENY} ladder.
     */
    Verdict evaluate(GovernedAction action, EnforcementMode mode);

    /** Gate outcome consumed by {@link ContractGateway}. */
    sealed interface Verdict {

        /**
         * Proceed; non-empty {@code flagNotes} lift the final decision to
         * {@code ALLOW_WITH_FLAG} and are cited on the row (never silent —
         * the §8.2 "every unregistered touch emits a signals row" discipline
         * rides the always-rowed flag).
         */
        record Allow(List<String> flagNotes) implements Verdict {

            public Allow {
                flagNotes = flagNotes == null ? List.of() : List.copyOf(flagNotes);
            }

            public static Allow clean() {
                return new Allow(List.of());
            }
        }

        /**
         * Deny with a §8.3/§8.2 reason code: enforce ⇒ DENY row +
         * {@link AgentGateDeniedException}; shadow ⇒ WOULD_DENY row +
         * proceed flagged (shadow never blocks, ADR-002 D4).
         */
        record Deny(String reason, String detail) implements Verdict {
        }
    }
}
