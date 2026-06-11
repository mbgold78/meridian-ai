package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.enums.AgentWireStatus;

/**
 * The §6.2 verb response (engine-spec agents.md; ADR-003 D6 honest-verbs
 * rule 4): {@code {status, enforcement{effect, confirmationState,
 * disclosure}}} + the refreshed {@link AgentDetail} so a scripted operator
 * sees the post-verb state without a second read. Pause / kill / retire /
 * decline-shaped outcomes ride this shape at HTTP 200 — never error-colored
 * (spec non-negotiable #2 / invariant I5).
 */
public record AgentVerbResponse(
        AgentWireStatus status,
        AgentEnforcement enforcement,
        AgentDetail agent) {
}
