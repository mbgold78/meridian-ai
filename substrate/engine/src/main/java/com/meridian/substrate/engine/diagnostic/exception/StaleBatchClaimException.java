package com.meridian.substrate.engine.diagnostic.exception;

import java.util.UUID;

/**
 * A batch commit (or terminal-state write) was attempted under a claim that
 * is no longer current — the batch row's {@code attempt}/{@code status} no
 * longer match the claim that produced the generation (engine-spec
 * diagnostic.md §4.3/§4.4 stale-claim rule).
 *
 * <p>This is the fence that keeps a slow-but-alive executor honest: when
 * {@code failStaleBatches} flips a RUNNING claim to FAILED and a resume
 * re-claims and re-generates, the ORIGINAL executor's late commit must be
 * refused — otherwise one batch commits twice (duplicate findings, double
 * token/cost accounting, last-writer-wins ledger counters). The refused
 * executor must NOT touch the row either: another claimant owns it now.
 */
public class StaleBatchClaimException extends RuntimeException {

    private final UUID batchId;

    public StaleBatchClaimException(UUID batchId, String message) {
        super(message);
        this.batchId = batchId;
    }

    public UUID batchId() {
        return batchId;
    }
}
