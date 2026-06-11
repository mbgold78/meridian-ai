package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Read-time liveness derivation (engine-spec agents.md §3/§10; ADR-003 D11):
 * OBSERVABLE when a telemetry channel exists (bound principal, or ORCHESTRATOR
 * locus with dispatch wiring); ATTEST_ONLY otherwise (CUSTOMER_SYSTEM without
 * MCP binding) — rendered honestly, never a fabricated green dot.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentObservability {
    OBSERVABLE,
    ATTEST_ONLY;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
