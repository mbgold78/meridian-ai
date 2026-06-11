package com.meridian.substrate.common.domain.enums;

/**
 * Outcome of a single contract-monitor run (engine-spec contracts.md §2.1,
 * §2.5; ADR-002 D10).
 *
 * <p><b>Hard invariant (as data, {@link #mayOpenOrRefreshBreach()}):</b>
 * INDETERMINATE / NOT_DUE / ERROR NEVER open or refresh a breach.
 * INDETERMINATE produces a needs-input signal ({@code ADHERENCE_STALE} /
 * {@code ADHERENCE_UNOBSERVABLE} / {@code HUMAN_VERIFY_DUE}); ERROR (the
 * evaluator threw) is an admin signal; breach state stays untouched.
 */
public enum MonitorRunOutcome {
    COMPLIANT,
    BREACH_DETECTED,
    INDETERMINATE,
    NOT_DUE,
    ERROR;

    /** Only BREACH_DETECTED may open/refresh a breach (ADR D10, as data). */
    public boolean mayOpenOrRefreshBreach() {
        return this == BREACH_DETECTED;
    }
}
