package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.SynthesisRun;

/**
 * Default no-op {@link DiagnosticAttestationHook} (ADR-004 D12; ADR-001 D15
 * idiom) — registered {@code @ConditionalOnMissingBean} by
 * {@link DiagnosticEngineConfig}; a real attestation hook bean replaces it.
 */
public class NoopDiagnosticAttestationHook implements DiagnosticAttestationHook {

    @Override
    public void onRunFinalized(SynthesisRun run) {
        // deliberately empty — EXTENSION POINT(diagnostic-12) seam default
    }
}
