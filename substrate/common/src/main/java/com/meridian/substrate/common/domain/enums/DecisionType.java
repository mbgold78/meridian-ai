package com.meridian.substrate.common.domain.enums;

/**
 * Types of design decisions that can be made in response to findings.
 */
public enum DecisionType {
    CONSOLIDATE,
    RETIRE,
    INTEGRATE,
    DECOUPLE,
    MODERNIZE,
    ELIMINATE_MANUAL_PROCESS,
    AUTOMATE_MANUAL_PROCESS,
    FORMALIZE_MANUAL_PROCESS,
    ABSORB_MANUAL_PROCESS,

    /**
     * Substrate addition (NOT in homepoc — recorded deviation): ADR-002 D14
     * contract accountability anchor mirrored into the design-decision hash
     * chain by {@code engine.contract} (activation / decline / breach waiver
     * only, engine-spec contracts.md §10.7). The column is VARCHAR(50), so no
     * migration is needed; existing chains are unaffected (the canonical-JSON
     * layout pinned by {@code CanonicalJsonHashVectorTest} hashes
     * {@code name()} per row and is layout-stable under new enum values).
     */
    CONTRACT_ACCOUNTABILITY,

    /**
     * Substrate addition (recorded deviation, same posture as
     * {@link #CONTRACT_ACCOUNTABILITY}): ADR-003 D14 agent governance anchors
     * mirrored by {@code engine.agent}'s {@code AgentGovernanceChainMirror} —
     * bounded to exactly three event classes (engine-spec agents.md §11):
     * DEPLOYED (canonical JSON includes {@code criteria_digest} — mandate
     * creation is tamper-evident), PROMOTED (production entry), and
     * RETIRED/KILLED (termination, carrying {@code retired_reason}; both land
     * as {@link #AGENT_RETIRED}). The shared mirror mechanism is
     * {@code DecisionLogService.appendDecision} + its pinned canonical-JSON
     * layout in {@code engine.decision} — never forked (ADR-003 D14
     * coordination rule); the anchor field layout is frozen by
     * {@code AgentMirrorHashVectorTest} BEFORE enabling (port-map trap #3).
     */
    AGENT_DEPLOYED,
    /** See {@link #AGENT_DEPLOYED} — ADR-003 D14 agent promotion anchor. */
    AGENT_PROMOTED,
    /** See {@link #AGENT_DEPLOYED} — ADR-003 D14 agent termination anchor (kill AND orderly retire). */
    AGENT_RETIRED
}
