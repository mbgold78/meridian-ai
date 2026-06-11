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
    CONTRACT_ACCOUNTABILITY
}
