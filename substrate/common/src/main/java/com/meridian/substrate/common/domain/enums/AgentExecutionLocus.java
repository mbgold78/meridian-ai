package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Where an agent runtime actually executes (engine-spec agents.md §3; ADR-003
 * D6). The locus drives the honest per-verb control-effect derivation (§5.3):
 * substrate never claims to have stopped what it cannot reach.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentExecutionLocus {
    SUBSTRATE_MCP,
    ORCHESTRATOR,
    CUSTOMER_SYSTEM;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
