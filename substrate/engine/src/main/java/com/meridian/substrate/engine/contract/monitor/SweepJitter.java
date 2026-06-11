package com.meridian.substrate.engine.contract.monitor;

/**
 * Jitter source for {@code next_run_at} (engine-spec contracts.md §5.2:
 * "next cadence boundary + jitter ≤ 60 s"). A seam so tests stay
 * deterministic; the production default (declared in
 * {@code ContractEngineConfig}) draws 0–60 000 ms.
 */
@FunctionalInterface
public interface SweepJitter {

    /** Milliseconds to add to the next cadence boundary; MUST be ≤ 60 000. */
    long nextJitterMillis();

    static SweepJitter none() {
        return () -> 0L;
    }
}
