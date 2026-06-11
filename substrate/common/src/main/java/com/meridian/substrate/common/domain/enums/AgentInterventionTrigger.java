package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * What raised an intervention (engine-spec agents.md §3). DRIFT_METRIC is
 * reserved — no evaluator exists in v1 (EXTENSION POINT(agent-8), spec §17 #8).
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentInterventionTrigger {
    HUMAN,
    MONITOR,
    GATEWAY_VIOLATION,
    DRIFT_METRIC;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
