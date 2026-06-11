package com.meridian.substrate.app.core.service.read;

import com.meridian.substrate.engine.contract.read.ContractCardResponse;
import com.meridian.substrate.engine.contract.read.SignalsResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/v1/portfolio} body (engine-spec diagnostic.md §12/§12.7;
 * ADR-004 D13) — D5 computed statistics, severing fixture stat seam S6. A
 * Beacon subsidiary IS a Domain (ADR-001 D13); every number here is read-time
 * deterministic compute over existing tables — no stats tables, no LLM
 * anywhere in the path (P7: deterministic lenses compute, the LLM narrates).
 *
 * <p><b>Absent-vs-zero is structural</b> (non-negotiable #4): an absent agent
 * registry serializes {@code agents.available=false} with {@code null}
 * counts, never {@code 0}; domains with zero PhaseState rows are the separate
 * {@code unphased} count, never folded into the stall count; {@code
 * aiReadiness} is OMITTED — serialized {@code null} with the omission named
 * in {@code computedFrom} (no computation basis until the D3 readiness lens;
 * EXTENSION POINT(diagnostic-15)).
 *
 * <p>DTOs speak fixture vocabulary at the {@code BEACON_PORTFOLIO} swap seam:
 * {@code lifecyclePhase} is the kebab-case fixture union
 * ({@code design | map | plan | execute | monitor | automate}), {@code null}
 * when no sequenced phase is IN_PROGRESS/BLOCKED.
 */
public record PortfolioView(
        String companyName,
        List<SubsidiaryCard> subsidiaries,
        CoordinationSignals coordinationSignals,
        List<ContractCardResponse> crossPortfolioOpportunities,
        long unphased,
        ComputedFrom computedFrom
) {

    /**
     * One portfolio subsidiary (= one Domain). {@code phaseStatesUninitialized}
     * is the §12.7 needs-input signal (zero PhaseState rows); a domain whose
     * rows exist but none are IN_PROGRESS/BLOCKED (e.g. COMPLETE-only) has
     * {@code lifecyclePhase=null} with {@code phaseStatesUninitialized=false}
     * — distinguishable, never conflated. {@code aiReadiness} is always
     * {@code null} in v1 — omitted, not invented (spec §15 row 15).
     */
    public record SubsidiaryCard(
            UUID domainId,
            String name,
            String lifecyclePhase,
            boolean phaseStatesUninitialized,
            boolean lifecycleStalled,
            FindingRollup findingCounts,
            AgentStats agents,
            long pendingFederationInvitations,
            String aiReadiness
    ) {
    }

    /**
     * §12.6 finding rollups. {@code bySeverity} applies the operator default
     * filter ({@code curation_status NOT IN (REJECTED, SUPERSEDED) OR NULL});
     * {@code clientSurfaceBySeverity} additionally excludes
     * {@code PENDING_REVIEW} (the D10 curation gate) — a rejected AI claim
     * never inflates a dashboard. Null severities count under
     * {@code UNSPECIFIED} — surfaced, never silently dropped.
     */
    public record FindingRollup(
            Map<String, Long> bySeverity,
            long aiGenerated,
            long operatorAuthored,
            Map<String, Long> clientSurfaceBySeverity
    ) {
    }

    /**
     * D3 agent-registry stats, composed when the registry bean exists.
     * Absent registry ⇒ {@code available=false} and {@code null} counts —
     * the spec's named absent-vs-zero shape ({@code agentsAvailable:false}
     * vs {@code 0}, test plan #14).
     */
    public record AgentStats(
            boolean available,
            Integer total,
            Integer live,
            Integer shadow,
            Integer paused,
            Integer interventionRequired
    ) {

        /** The registry-absent shape: counts are ABSENT (null), never zero. */
        public static AgentStats absent() {
            return new AgentStats(false, null, null, null, null, null);
        }
    }

    /**
     * §12.7 coordination-signal strip: stall count + delegated coordination
     * patterns + decline-pending cascades (D1 attention feed) + in-intervention
     * agents (D3, null when the registry is absent) + contract needs-input
     * classes (§11.1 vocabulary: {@code ADHERENCE_STALE |
     * ADHERENCE_UNOBSERVABLE | HUMAN_VERIFY_DUE}).
     *
     * <p>{@code coordinationPatternsThisWeek} ≡ Σ over portfolio domains of
     * the contract engine's §11.2 number — same bean, never re-derived
     * (EXTENSION POINT(diagnostic-10): a richer union is a SignalService-spec
     * amendment, never a portfolio-side fork). {@code coordinationBreakdown}
     * carries the per-source sub-counts that computation already owns, so
     * pre-D2-3b the contract term contributes 0 <em>visibly</em>.
     */
    public record CoordinationSignals(
            long lifecycleStallCount,
            long coordinationPatternsThisWeek,
            SignalsResponse.CoordinationBreakdown coordinationBreakdown,
            long declinePendingCascades,
            boolean agentsAvailable,
            Integer agentsInIntervention,
            Map<String, Long> needsInputByKind,
            long pendingFederationInvitations
    ) {
    }

    /**
     * §12 meta block — every stats DTO names what it was computed from, so a
     * reviewer can distinguish "0 because clean" from "0 because a source is
     * absent". {@code aiReadiness} carries the omission note verbatim
     * (EXTENSION POINT(diagnostic-15)); {@code coordinationWindowDays} REPORTS
     * the contracts-owned §11.2 window — the value is delegated, never forked.
     */
    public record ComputedFrom(
            Instant computedAt,
            int stallThresholdDays,
            int coordinationWindowDays,
            boolean demoDomainsIncluded,
            int domainsConsidered,
            boolean agentRegistryAvailable,
            String aiReadiness,
            Map<String, String> sources
    ) {
    }
}
