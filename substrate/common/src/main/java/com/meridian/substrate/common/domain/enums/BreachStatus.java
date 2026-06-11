package com.meridian.substrate.common.domain.enums;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a {@link com.meridian.substrate.common.domain.ContractBreach}
 * (engine-spec contracts.md §2.4) — monotonic, forward-only; rows are never
 * deleted; every transition appends a {@code contract_events} row with actor
 * identity:
 * <pre>
 *   DETECTED → SURFACED → ACKNOWLEDGED → REMEDIATED | WAIVED
 * </pre>
 *
 * <p>WAIVED is human-only (agents may never waive — built-in non-removable
 * guard, ADR D16) and is mirrored to the hash chain (ADR D14).
 */
public enum BreachStatus {
    DETECTED,
    SURFACED,
    ACKNOWLEDGED,
    REMEDIATED,
    WAIVED;

    private static final Map<BreachStatus, Set<BreachStatus>> LEGAL_TRANSITIONS =
            new EnumMap<>(Map.of(
                    DETECTED, Set.of(SURFACED),
                    SURFACED, Set.of(ACKNOWLEDGED),
                    ACKNOWLEDGED, Set.of(REMEDIATED, WAIVED),
                    REMEDIATED, Set.of(),
                    WAIVED, Set.of()));

    /** Legal transition targets from this status (spec §2.4, as data). */
    public Set<BreachStatus> legalTargets() {
        return LEGAL_TRANSITIONS.get(this);
    }

    public boolean canTransitionTo(BreachStatus target) {
        return legalTargets().contains(target);
    }

    /**
     * Open = participates in the dedup invariant (at most one open breach per
     * {@code (monitor_id, commitment_id, period_key)} — ADR D13). Matches the
     * V5_1 partial-index predicate {@code status IN
     * ('DETECTED','SURFACED','ACKNOWLEDGED')} exactly.
     */
    public boolean isOpen() {
        return this == DETECTED || this == SURFACED || this == ACKNOWLEDGED;
    }

    public boolean isTerminal() {
        return legalTargets().isEmpty();
    }
}
