package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.SynthesisRun;

/**
 * Run-finalization attestation seam (engine-spec diagnostic.md §1/§4.5;
 * ADR-004 D12, the ADR-001 D15 hook idiom). Fires LAST in the finalization
 * transaction; the default is {@link NoopDiagnosticAttestationHook}.
 *
 * <p>Per-item finding curation is deliberately NOT mirrored into the
 * {@code engine.decision} hash chain (volume / append-lock contention —
 * ADR-002 D14's bounded-mirroring rationale).
 *
 * <p>EXTENSION POINT(diagnostic-12): decision-log attestation of curation.
 * A real hook can anchor a per-run digest (run id + ordered accepted-finding
 * hashes) behind {@code substrate.diagnostic.attest-to-decision-log}
 * (default false). Unblocks via regulated-buyer demand for curation
 * tamper-evidence; flag, don't invent.
 */
public interface DiagnosticAttestationHook {

    /** Called inside the run-finalization transaction, after the terminal status is set. */
    void onRunFinalized(SynthesisRun run);
}
