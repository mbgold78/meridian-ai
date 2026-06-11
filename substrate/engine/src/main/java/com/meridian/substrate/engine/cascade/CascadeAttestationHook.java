package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevelAction;

/**
 * Cross-engine attestation seam, invoked by {@code CascadeLifecycleService} on
 * terminal resolution (RESOLVE_LOOKS_GOOD / RESOLVE_DECLINED) inside the
 * resolve transaction (engine-spec cascade.md §1; ADR-001 D15).
 *
 * <p>EXTENSION POINT(cascade-10): decision-log attestation — the designed-but-
 * not-built implementation anchors a SHA-256 digest of the cascade's
 * canonical-serialized action log into the existing {@code engine.decision}
 * hash chain, behind {@code substrate.cascade.attest-to-decision-log}
 * (default false). NOT implemented in Phase 2; {@link NoopCascadeAttestationHook}
 * is the default bean (spec §13.10).
 */
public interface CascadeAttestationHook {

    /**
     * Called after the terminal RESOLVE_* action and all projections have been
     * applied, before the transaction commits.
     */
    void onResolved(Cascade cascade, CascadeLevelAction resolveAction);
}
