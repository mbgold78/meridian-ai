package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Rule type of an {@code agent_criteria_gates} row (engine-spec agents.md
 * §3/§9; ADR-003 D9): STRUCTURED carries a contracts-TinyDsl predicate
 * (verbatim reuse — no second DSL); UNSTRUCTURED is un-compilable prose
 * (zero PHI leakage is the canonical fixture example) — legitimate
 * human-judgment criteria, flagged not faked, with predicate NULL.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentGateRuleType {
    STRUCTURED,
    UNSTRUCTURED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
