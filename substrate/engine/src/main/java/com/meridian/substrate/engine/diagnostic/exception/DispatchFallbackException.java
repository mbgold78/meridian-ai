package com.meridian.substrate.engine.diagnostic.exception;

/**
 * Signals that the orchestrated generation lane could not produce a result
 * for THIS batch and the configured fallback applies (engine-spec
 * diagnostic.md §8.2; ADR-004 D11 graft): budget refusal or dispatch failure
 * → per {@code substrate.diagnostic.dispatch.fallback} the batch executor
 * either re-executes the batch on the in-process executor (recorded
 * {@code executor='in-process-fallback'}) or marks it {@code SKIPPED_BUDGET}
 * (neutral, non-error — budget refusal is a governance non-event per the
 * orchestrator-substrate contract doc; SKIPPED_BUDGET never blocks run
 * COMPLETED).
 *
 * <p>Deliberately NOT thrown for a contract-gateway DENY of
 * {@code apply_dispatch_result}: a governance denial is neither a budget nor
 * a transport outcome — it propagates as a generation failure and the batch
 * goes FAILED with the denial cited (resumable; never silently re-routed
 * around the PDP).
 */
public class DispatchFallbackException extends RuntimeException {

    /** Why the dispatch lane yielded nothing for this batch. */
    public enum Reason {
        /** {@code check_budget_for_task} refused — the governance non-event. */
        BUDGET_REFUSED,
        /** Submit/poll/result transport failed, timed out, or the dispatch ended FAILED. */
        DISPATCH_FAILED
    }

    private final Reason reason;

    private DispatchFallbackException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static DispatchFallbackException budgetRefused(String note) {
        return new DispatchFallbackException(Reason.BUDGET_REFUSED,
                "budget refused: " + (note == null ? "(no note)" : note));
    }

    public static DispatchFallbackException dispatchFailed(String detail) {
        return new DispatchFallbackException(Reason.DISPATCH_FAILED, detail);
    }

    public Reason reason() {
        return reason;
    }
}
