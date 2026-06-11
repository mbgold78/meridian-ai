package com.meridian.substrate.engine.contract.gateway;

/**
 * Canonical governed action-type vocabulary for the engine-module PEPs
 * (engine-spec contracts.md §4.2; ADR-002 D2/D16). These strings are the
 * guard-matching currency ({@code contract_clause_guards.action_type}) and
 * are deliberately TRANSPORT-NEUTRAL: the REST service command and the §8.3
 * MCP write tool for the same verb evaluate the SAME action type — guards
 * govern actions, not transports. Naming follows the
 * {@code contract.invoke_decline} precedent ({@code <aggregate>.<verb>});
 * {@code apply_dispatch_result} keeps its spec-§4.1 bare spelling.
 *
 * <p>App-module governed services (process steps, reader registrations,
 * phase states) declare their action types at their own annotations — this
 * class is the ENGINE vocabulary, not a global registry.
 */
public final class GovernedActions {

    // ----------------------------------------------------- contract registry
    public static final String CONTRACT_CREATE = "contract.create";
    public static final String CONTRACT_PATCH = "contract.patch";
    public static final String CONTRACT_RESPOND = "contract.respond";
    /** §6.1 — also the action the §6 decline advisory lanes row under. */
    public static final String CONTRACT_INVOKE_DECLINE = "contract.invoke_decline";

    // ----------------------------------------- attestations / metrics (§5.5)
    public static final String CONTRACT_ATTEST_COMMITMENT = "contract.attest_commitment";
    public static final String CONTRACT_RECORD_COMMITMENT_PROGRESS =
            "contract.record_commitment_progress";
    public static final String CONTRACT_REPORT_METRIC = "contract.report_metric";

    // ------------------------------------------------ breach human verbs (§2.4)
    public static final String CONTRACT_ACKNOWLEDGE_BREACH = "contract.acknowledge_breach";
    public static final String CONTRACT_REMEDIATE_BREACH = "contract.remediate_breach";
    /** ADR D16 — the built-in non-removable AGENT deny watches this name. */
    public static final String CONTRACT_WAIVE_BREACH = ContractGateway.ACTION_WAIVE_BREACH;

    // ------------------------------------------------- §4.2 PEP 3 (dispatch)
    /** Spec-§4.1 spelling; carried with a non-null {@code dispatchId}. */
    public static final String APPLY_DISPATCH_RESULT = "apply_dispatch_result";

    // ----------------------------------------------- agent registry (ADR-003)
    /**
     * Agent deploy transits the gateway as a HUMAN-principal governed action
     * (engine-spec agents.md §5.4) — a contract can guard agent deployment.
     * The 6c built-in non-removable guard additionally denies AGENT
     * principals on this action (agents never operate lifecycle, §8.4).
     */
    public static final String AGENT_DEPLOY = "agent.deploy";
    /** The contracts 3c acceptance seeds a DENY guard on this name (§8.4); verb wired at 6b. */
    public static final String AGENT_PROMOTE = "agent.promote";
    /** agents.md §12 — MCP {@code agent_heartbeat} (HEARTBEAT sighting; observation scope). */
    public static final String AGENT_HEARTBEAT = "agent.heartbeat";
    /** agents.md §12 — MCP {@code report_agent_activity} (sightings + metric samples). */
    public static final String AGENT_REPORT_ACTIVITY = "agent.report_activity";
    /**
     * agents.md §12/§5.1 M17 — MCP {@code acknowledge_control}: the ONE
     * governed write a PAUSED/RETIRED agent principal retains (§8.3 check 1
     * exception — acknowledging shutdown is the compliant act).
     */
    public static final String AGENT_ACKNOWLEDGE_CONTROL = "agent.acknowledge_control";

    // --------------------------------------------------- cascade engine (D2)
    public static final String CASCADE_CREATE_DRAFT = "cascade.create_draft";
    public static final String CASCADE_PATCH_DRAFT = "cascade.patch_draft";
    public static final String CASCADE_DISCARD_DRAFT = "cascade.discard_draft";
    public static final String CASCADE_SUGGEST_DRAFT = "cascade.suggest_draft";
    public static final String CASCADE_ISSUE = "cascade.issue";
    public static final String CASCADE_CASCADE = "cascade.cascade";
    public static final String CASCADE_ABSORB = "cascade.absorb";
    public static final String CASCADE_DECLINE = "cascade.decline";
    public static final String CASCADE_RECASCADE = "cascade.recascade";
    public static final String CASCADE_RESOLVE = "cascade.resolve";

    private GovernedActions() {
    }
}
