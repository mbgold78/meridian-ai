package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * The declared enforcement effect of an Ops Center verb (engine-spec agents.md
 * §3/§5.3; ADR-003 D6 honest-verbs): {@code GATEWAY_BLOCK} — the registry flip
 * IS the control plane (gateway denies the next governed call); or
 * {@code RECORDED_INTENT} — substrate cannot reach the runtime; the verb
 * records intent, notifies the oversight chain and tracks acknowledgment /
 * confirmation. Disclosed in every verb response and the detail DTO.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentControlEffect {
    GATEWAY_BLOCK,
    RECORDED_INTENT;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
