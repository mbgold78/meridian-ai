package com.meridian.substrate.common.domain.enums;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Denormalized compliance state on
 * {@link com.meridian.substrate.common.domain.ContractCommitment}
 * (engine-spec contracts.md §2.3; history lives in {@code contract_events}).
 *
 * <p>Driven ONLY by monitor runs, attestation claims, and breach transitions —
 * never directly editable via API. Evaluable only while the contract is ACTIVE.
 *
 * <p>Transition data encodes the §2.3 diagram plus its text-documented
 * breach-entry edges:
 * <ul>
 *   <li>{@code BREACHED} ⇔ an open breach row references the commitment, so any
 *       pre-breach state may enter BREACHED when a breach opens (a MISSED
 *       attestation auto-opens one — §2.3, ADR D13);</li>
 *   <li>{@code BREACHED → COMPLIANT} on breach REMEDIATED;
 *       {@code BREACHED → WAIVED} on breach WAIVED (§2.4).</li>
 * </ul>
 */
public enum CommitmentComplianceState {
    UNEVALUATED,
    COMPLIANT,
    AT_RISK,
    BREACHED,
    WAIVED;

    private static final Map<CommitmentComplianceState, Set<CommitmentComplianceState>> LEGAL_TRANSITIONS =
            new EnumMap<>(Map.of(
                    UNEVALUATED, Set.of(COMPLIANT, AT_RISK, BREACHED),
                    COMPLIANT, Set.of(AT_RISK, BREACHED),
                    AT_RISK, Set.of(COMPLIANT, BREACHED),
                    BREACHED, Set.of(COMPLIANT, WAIVED),
                    WAIVED, Set.of()));

    /** Legal transition targets from this state (spec §2.3, as data). */
    public Set<CommitmentComplianceState> legalTargets() {
        return LEGAL_TRANSITIONS.get(this);
    }

    public boolean canTransitionTo(CommitmentComplianceState target) {
        return legalTargets().contains(target);
    }
}
