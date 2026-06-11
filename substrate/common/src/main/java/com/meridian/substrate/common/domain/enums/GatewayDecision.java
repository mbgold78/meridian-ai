package com.meridian.substrate.common.domain.enums;

/**
 * Append-only enforcement-gateway decision outcome (engine-spec contracts.md
 * §2.7, §4; ADR-002 D4/D17). Not an entity state machine. {@code WOULD_DENY}
 * is the shadow-mode record of a denial that was NOT enforced.
 */
public enum GatewayDecision {
    ALLOW,
    ALLOW_WITH_FLAG,
    DENY,
    WOULD_DENY
}
