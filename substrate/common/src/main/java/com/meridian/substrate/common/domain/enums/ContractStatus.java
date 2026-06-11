package com.meridian.substrate.common.domain.enums;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle status of a {@link com.meridian.substrate.common.domain.Contract}
 * (engine-spec contracts.md §2.1/§2.2; ADR-002 D7).
 *
 * <p>The CONTRACT state machine is encoded as data here ({@link #legalTargets()}):
 * <pre>
 *   PROPOSED → ACTIVE     (all required parties ACCEPTED)
 *   PROPOSED → DECLINED   (any required party responds DECLINED)
 *   ACTIVE   → DECLINED   (invoke_decline — cites pre-agreed trigger, reasoning mandatory)
 * </pre>
 *
 * <p>DECLINED is a TERMINAL, LEGITIMATE governance outcome — never error-coded,
 * never breach-colored (port-map trap #8; [CONTRACT-COMMITMENT-STATES]).
 *
 * <p>{@code WITHDRAWN} and {@code CONCLUDED} are schema-reserved and
 * transition-UNREACHABLE in v1 (ADR D7; spec §15 extension point #2 —
 * completion/withdrawal semantics appear in no fixture or doc; they unblock at
 * the first contract reaching natural end). No transition set contains them
 * and they have no outbound edges — asserted by test.
 */
public enum ContractStatus {
    PROPOSED,
    ACTIVE,
    DECLINED,
    /** Schema-reserved, transition-unreachable in v1 (ADR D7). */
    WITHDRAWN,
    /** Schema-reserved, transition-unreachable in v1 (ADR D7). */
    CONCLUDED;

    private static final Map<ContractStatus, Set<ContractStatus>> LEGAL_TRANSITIONS =
            new EnumMap<>(Map.of(
                    PROPOSED, Set.of(ACTIVE, DECLINED),
                    ACTIVE, Set.of(DECLINED),
                    DECLINED, Set.of(),
                    WITHDRAWN, Set.of(),
                    CONCLUDED, Set.of()));

    /** Legal transition targets from this status (spec §2.2, as data). */
    public Set<ContractStatus> legalTargets() {
        return LEGAL_TRANSITIONS.get(this);
    }

    public boolean canTransitionTo(ContractStatus target) {
        return legalTargets().contains(target);
    }

    /** No outbound edges. DECLINED is the only REACHABLE terminal in v1. */
    public boolean isTerminal() {
        return legalTargets().isEmpty();
    }

    /** ADR D7: enum value exists in the schema but no code path may construct it. */
    public boolean isSchemaReservedUnreachable() {
        return this == WITHDRAWN || this == CONCLUDED;
    }
}
