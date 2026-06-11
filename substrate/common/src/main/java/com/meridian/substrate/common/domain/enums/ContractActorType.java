package com.meridian.substrate.common.domain.enums;

/**
 * Actor type on {@code contract_events} rows (engine-spec contracts.md §2.1).
 * Superset of {@link PrincipalType}: {@code MONITOR} and {@code SYSTEM} are
 * engine-internal actors (sweep runs, lifecycle side effects).
 */
public enum ContractActorType {
    HUMAN,
    AGENT,
    SERVICE,
    MONITOR,
    SYSTEM
}
