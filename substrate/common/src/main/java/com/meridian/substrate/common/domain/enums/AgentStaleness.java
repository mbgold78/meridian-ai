package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Read-time staleness derivation (engine-spec agents.md §3/§10; ADR-003 D11).
 * STALE only when an expectation exists and is exceeded;
 * {@code heartbeat_expected_minutes} NULL renders NOT_EXPECTED (not expected
 * to report), never stale-red.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentStaleness {
    FRESH,
    STALE,
    NEVER_SEEN,
    NOT_EXPECTED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
