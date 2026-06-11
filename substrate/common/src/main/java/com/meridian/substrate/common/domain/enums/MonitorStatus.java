package com.meridian.substrate.common.domain.enums;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Status of a {@link com.meridian.substrate.common.domain.ContractMonitor}
 * (engine-spec contracts.md §2.5): {@code ACTIVE ⇄ SUSPENDED}. Monitors are
 * armed only while the contract is ACTIVE; ALL are suspended atomically on
 * decline (§2.2).
 */
public enum MonitorStatus {
    ACTIVE,
    SUSPENDED;

    private static final Map<MonitorStatus, Set<MonitorStatus>> LEGAL_TRANSITIONS =
            new EnumMap<>(Map.of(
                    ACTIVE, Set.of(SUSPENDED),
                    SUSPENDED, Set.of(ACTIVE)));

    /** Legal transition targets from this status (spec §2.5, as data). */
    public Set<MonitorStatus> legalTargets() {
        return LEGAL_TRANSITIONS.get(this);
    }

    public boolean canTransitionTo(MonitorStatus target) {
        return legalTargets().contains(target);
    }
}
