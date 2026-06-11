package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.enums.AgentControlConfirmationState;
import com.meridian.substrate.common.domain.enums.AgentControlEffect;

/**
 * The honest-control block carried by EVERY verb response (engine-spec
 * agents.md §5.3/§6.2; ADR-003 D6): the declared enforcement effect, the
 * confirmation state of the control, and a plain-language disclosure of what
 * the verb actually did. Scripted operators and Testers get the same honesty
 * as the UI — a RECORDED_INTENT verb response never claims enforcement
 * (invariant I8); the 6b→6c window disclosure ("enforcement pending gateway")
 * is an ACCEPTANCE ITEM, not a note (ADR-003 D17).
 */
public record AgentEnforcement(
        AgentControlEffect effect,
        AgentControlConfirmationState confirmationState,
        String disclosure) {
}
