package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.enums.AgentControlConfirmationState;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /agents/signals?domainId} (engine-spec agents.md §13.1) — severs
 * the S6 fixture-stamped fleet stats. <b>Explicit zeros are ALWAYS present</b>
 * (no-silent-zero-yield): an empty domain serializes every count as {@code 0}
 * and every list as {@code []}, never as an absent field. Computed at read
 * from existing tables — no signal table exists.
 *
 * <p>The swapped OperationsTab strip maps "Agents active" ← {@code agentsTotal}
 * (the fixture card counts the whole non-retired fleet — behavior preserved,
 * renaming is a flagged post-swap UI question) and "Agents in intervention" ←
 * {@code agentsInterventionRequired}; the open-findings count stays on the
 * findings API.
 *
 * <p>{@code budgetsExhausted} is schema-shaped but ALWAYS empty until the 6c
 * ACTIONS_PER_WINDOW evaluator lands; {@code gateCoverage} keeps degradation
 * visible — STRUCTURED gates stand UNEVALUATED until the evaluation pass
 * (6b/6c) runs, and the ratio says so instead of staying silent.
 */
public record AgentSignals(
        int agentsTotal,                        // whole non-retired fleet (fixture card behavior)
        int agentsLive,
        int agentsShadow,
        int agentsPaused,
        int agentsInterventionRequired,         // wire-status projection counts (§5.2)
        Interventions interventions,
        List<UnconfirmedControl> unconfirmedControls,
        List<ControlViolation> controlViolations,
        Liveness liveness,
        List<PromotionPending> promotionsPending,
        List<BudgetExhausted> budgetsExhausted, // [] until 6c — explicit, never absent
        GateCoverage gateCoverage
) {

    public record Interventions(int open, Instant oldestOpenedAt) {
    }

    /** A RECORDED_INTENT control awaiting runtime ack / human confirmation (ADR-003 D6). */
    public record UnconfirmedControl(String agentKey, String verb, Instant requestedAt,
                                     AgentControlConfirmationState confirmationState) {
    }

    /** Zombie-signal classes (§6.4): ACTIVE_WHILE_PAUSED | SEEN_AFTER_KILL (6c flows raise them). */
    public record ControlViolation(String agentKey, String kind, Instant lastSeenAt) {
    }

    public record Liveness(int stale, int neverSeen, int attestOnly) {
    }

    public record PromotionPending(String agentKey, Instant requestedAt) {
    }

    /** Shape reserved for the 6c ACTIONS_PER_WINDOW evaluator; no rows exist before it. */
    public record BudgetExhausted(String agentKey, String budgetKind) {
    }

    /** Degradation visible, never silent (§9.2): structured/unstructured by rule type; unevaluated by standing. */
    public record GateCoverage(int structured, int unstructured, int unevaluated) {
    }
}
