package com.meridian.substrate.engine.contract;

import com.meridian.substrate.common.domain.Contract;

/**
 * Seam for the ADR D14 hash-chain mirror: contract activation, decline and
 * breach WAIVER are mirrored into the existing {@code engine.decision} SHA-256
 * chain — bounded to exactly those events, via {@code DecisionLogService}
 * under its per-domain append lock, NEVER by writing the chain directly
 * (spec §10.7).
 *
 * <p>Implemented since 3b-2 by {@link DecisionLogContractAccountabilityMirror}
 * (the {@code ContractEngineConfig} default): port-map trap #3 / ADR D14
 * required the contract-transition hash-vector test to be PINNED BEFORE
 * enabling mirroring — {@code ContractMirrorHashVectorTest} freezes the anchor
 * layout, and the §14.4 Testcontainers lane re-proves the chain on real
 * Postgres in CI. {@link NoopContractAccountabilityMirror} remains as an
 * explicit opt-out/test seam. Chain inputs must reference the immutable
 * {@code contract_key}/clause ids, never display titles
 * ([DECISIONLOG-COVERAGE]).
 */
public interface ContractAccountabilityMirror {

    /** PROPOSED → ACTIVE (final required acceptance) — spec §2.2 hash anchor. */
    void onActivated(Contract contract);

    /** PROPOSED → DECLINED and ACTIVE → DECLINED — spec §2.2 hash anchor. */
    void onDeclined(Contract contract);

    /**
     * Breach WAIVED (human-only, ADR D16) — the third and last bounded ADR D14
     * mirror anchor ("waiver is exactly the action a regulated buyer wants
     * tamper-evident").
     */
    void onBreachWaived(Contract contract, com.meridian.substrate.common.domain.ContractBreach breach);
}
