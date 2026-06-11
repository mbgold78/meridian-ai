package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;

import java.util.List;
import java.util.UUID;

/**
 * Pure result of one {@link MonitorEvaluator} pass (engine-spec contracts.md
 * §5.3). Evaluators only DESCRIBE what they found — every side effect
 * (run row, monitor projection, breach open/refresh, commitment state)
 * is applied by {@link MonitorRunService}, which structurally enforces the
 * ADR D10 hard invariant: breach candidates are applied ONLY when
 * {@code outcome.mayOpenOrRefreshBreach()} (i.e. BREACH_DETECTED) — an
 * INDETERMINATE / NOT_DUE / ERROR run can never open or refresh a breach.
 *
 * @param outcome          §2.5 run outcome
 * @param periodKey        evaluated period (run-row column; null when no §5.1
 *                         key semantics apply)
 * @param trace            bounded-later evaluation trace (rule, inputs
 *                         resolved, counts/values — ADR D10 graft)
 * @param breaches         candidates applied only on BREACH_DETECTED
 * @param commitmentStates monitor-driven COMPLIANT/AT_RISK projections
 *                         (§2.3; BREACHED entry happens inside BreachService)
 */
public record MonitorEvaluation(MonitorRunOutcome outcome,
                                String periodKey,
                                ObjectNode trace,
                                List<BreachCandidate> breaches,
                                List<CommitmentState> commitmentStates) {

    /**
     * One would-be breach source (§5.4 dedup key components; the service adds
     * contract/monitor identity, severity and detected_by).
     */
    public record BreachCandidate(UUID commitmentId, String periodKey, String summary) {
    }

    /**
     * Monitor-driven commitment compliance projection (§5.3): only COMPLIANT
     * and AT_RISK are legal here — BREACHED/WAIVED belong to the breach
     * lifecycle (§2.3/§2.4).
     */
    public record CommitmentState(UUID commitmentId, CommitmentComplianceState state) {

        public CommitmentState {
            if (state != CommitmentComplianceState.COMPLIANT
                    && state != CommitmentComplianceState.AT_RISK) {
                throw new IllegalArgumentException(
                        "evaluators may project only COMPLIANT/AT_RISK (was " + state
                                + ") — BREACHED/WAIVED are breach-lifecycle states (§2.3)");
            }
        }
    }

    public static MonitorEvaluation of(MonitorRunOutcome outcome, String periodKey, ObjectNode trace) {
        return new MonitorEvaluation(outcome, periodKey, trace, List.of(), List.of());
    }
}
