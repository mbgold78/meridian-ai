package com.meridian.substrate.engine.contract.read;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /contracts/signals?domainId} body (engine-spec contracts.md
 * §11.2) — severs fixture stat seam S6. Computed at READ TIME from existing
 * tables; no signal table exists in v1.
 *
 * <p>Three signal classes render distinctly (ADR D10 — never down-render to
 * green): leader-attention ({@code openBreaches}, {@code declineEligible} —
 * the latter a subtle-destructive accent, NEVER a breach), needs-input
 * ({@code needsInput}), and the {@code monitorCoverage} visibility ratio
 * (structured vs UNSTRUCTURED monitors — rubber-stamp degradation stays
 * visible; never drop it from the dashboard, spec §16).
 */
public record SignalsResponse(
        long pendingFederationInvitations,
        OpenBreaches openBreaches,
        List<DeclineEligibleItem> declineEligible,
        List<NeedsInputItem> needsInput,
        MonitorCoverage monitorCoverage,
        long coordinationPatternsThisWeek,
        List<ReviewBoundaryDueItem> reviewBoundariesDue,
        CoordinationBreakdown coordinationBreakdown
) {

    /**
     * Compatibility constructor (pre-D5 arity): callers/tests written before
     * the additive {@code coordinationBreakdown} component compile UNMODIFIED;
     * the breakdown defaults to null (absent — never fabricated zeros).
     */
    public SignalsResponse(long pendingFederationInvitations,
                           OpenBreaches openBreaches,
                           List<DeclineEligibleItem> declineEligible,
                           List<NeedsInputItem> needsInput,
                           MonitorCoverage monitorCoverage,
                           long coordinationPatternsThisWeek,
                           List<ReviewBoundaryDueItem> reviewBoundariesDue) {
        this(pendingFederationInvitations, openBreaches, declineEligible, needsInput,
                monitorCoverage, coordinationPatternsThisWeek, reviewBoundariesDue, null);
    }

    /** Open-breach count + severity histogram across the domain's contracts. */
    public record OpenBreaches(long total, Map<String, Long> bySeverity) {
    }

    /** An ARMED pre-agreed decline trigger (§2.6 — legitimate, never a breach). */
    public record DeclineEligibleItem(String contractKey, UUID triggerId, String text) {
    }

    /**
     * Needs-input signal (ADR D10): {@code kind} is one of
     * {@code ADHERENCE_STALE | ADHERENCE_UNOBSERVABLE | HUMAN_VERIFY_DUE},
     * derived from the monitor's rule type on an INDETERMINATE last outcome.
     * INDETERMINATE never opens or refreshes a breach.
     */
    public record NeedsInputItem(String contractKey, UUID monitorId, String kind) {
    }

    /** ADR D10 visibility ratio over all monitors of the domain's contracts. */
    public record MonitorCoverage(long structured, long unstructured) {
    }

    /**
     * Populated only for contracts with a non-null {@code review_boundary}.
     * EXTENSION POINT (spec §15 #1): boundary-window arithmetic (§6.2) lands
     * with the 3c gateway; until then this list is computed empty — both
     * DemoPack vectors seed {@code review_boundary = null} by design.
     */
    public record ReviewBoundaryDueItem(String contractKey) {
    }

    /**
     * Per-source sub-counts of {@code coordinationPatternsThisWeek}
     * (engine-spec diagnostic.md §12.2; ADR-004 D13): the sub-terms this
     * computation ALREADY owns, exposed so the D5 portfolio surface can show
     * that pre-D2-3b the contract term contributes 0 <em>visibly</em> — one
     * computation, two surfaces, zero drift. Always satisfies
     * {@code contractEvents + cascadeActions == coordinationPatternsThisWeek}.
     */
    public record CoordinationBreakdown(long contractEvents, long cascadeActions) {
    }
}
