package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Confirmation tracking for RECORDED_INTENT controls (engine-spec agents.md
 * §3/§5.3; ADR-003 D6): runtime acknowledgment (MCP {@code acknowledge_control})
 * and human-attested confirmation (REST confirm-control) are DISTINCT facts;
 * neither auto-derives from the other, and sighting absence flips nothing.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentControlConfirmationState {
    NOT_REQUIRED,
    PENDING,
    ACKNOWLEDGED,
    CONFIRMED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
