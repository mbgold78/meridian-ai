package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Decision on a compliance review (engine-spec agents.md §3; ADR-003 D10).
 * REJECTED renders info/neutral — a rejected promotion review is a legitimate
 * governance outcome, re-requestable, never error-colored (trap #8);
 * NEEDS_MORE_TIME keeps the request open.
 *
 * <p>DB stores enum names ({@code VARCHAR}); the wire form is kebab-case via
 * {@link JsonValue} (engine-spec agents.md §3). No error/failure vocabulary
 * exists in any agent enum by design (spec non-negotiable #2).
 */
public enum AgentReviewDecision {
    APPROVED,
    REJECTED,
    NEEDS_MORE_TIME;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
