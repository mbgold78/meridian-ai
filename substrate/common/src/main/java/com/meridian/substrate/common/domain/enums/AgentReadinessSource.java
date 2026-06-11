package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Provenance of {@code agent_readiness_score} (engine-spec agents.md §3;
 * ADR-003 D3): a caller-asserted score is never laundered as a deterministic
 * readiness-lens computation (P7 discipline). Service rule: NOT NULL iff a
 * score is present.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentReadinessSource {
    READINESS_LENS,
    OPERATOR_ASSERTED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
