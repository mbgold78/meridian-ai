package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a registration reached terminal RETIRED mode (engine-spec agents.md §3;
 * ADR-003 D4, Mike-flagged distinction): KILLED = emergency stop, RETIRED =
 * orderly retirement. Both are legitimate human governance acts (HTTP 200,
 * never error-colored); both are chain-mirrored at 6b (ADR-003 D14).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentRetiredReason {
    KILLED,
    RETIRED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
