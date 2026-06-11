package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevelAction;

/**
 * Default no-op {@link CascadeAttestationHook} (ADR-001 D15). Registered by
 * {@link CascadeEngineConfig} only when no other hook bean is present.
 */
public class NoopCascadeAttestationHook implements CascadeAttestationHook {

    @Override
    public void onResolved(Cascade cascade, CascadeLevelAction resolveAction) {
        // EXTENSION POINT(cascade-10): decision-log attestation is deliberately
        // NOT implemented in Phase 2 (spec §13.10; ADR D15).
    }
}
