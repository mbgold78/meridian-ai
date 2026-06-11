package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.enums.AgentControlEffect;

/**
 * One {@code controlEffects} entry on the detail DTO (engine-spec agents.md
 * §6.1; ADR-003 D6): "disclosure BEFORE the click" — the UI renders what a
 * verb will actually do for this agent's execution locus before the operator
 * clicks it.
 */
public record AgentEffectDisclosure(AgentControlEffect effect, String disclosure) {
}
