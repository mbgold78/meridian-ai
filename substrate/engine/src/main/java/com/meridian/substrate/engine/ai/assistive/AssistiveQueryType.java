package com.meridian.substrate.engine.ai.assistive;

/**
 * The Phase 2A allowlist of implemented assistive query types. Anything outside
 * this enum is rejected by {@link AssistiveModeService} with an explicit
 * "not yet implemented" explanation rather than silently routed to a generic
 * handler — Phase 2B adds query types as the Capability Designer UI surfaces
 * them.
 *
 * <p>Each query type declares its {@link GroundingRule} as part of its
 * definition. The service reads {@link #getGroundingRule()} to decide whether
 * to enforce evidence filtering — the rule lives at the type definition (where
 * it is discoverable next to the type's name and Javadoc) rather than in
 * service-side branching, so future query types are self-documenting about
 * their grounding contract.
 */
public enum AssistiveQueryType {

    /**
     * Suggest a description for the entity the user is editing, drawn from
     * Universe content. Grounding required.
     */
    SUGGEST_DESCRIPTION(GroundingRule.REQUIRED),

    /**
     * Detect whether the proposed name collides with existing capability names
     * at the same level. Structural — no Universe evidence required.
     */
    DETECT_NAME_COLLISION(GroundingRule.NOT_REQUIRED),

    /**
     * Suggest a parent capability for the entity being authored, based on the
     * existing capability tree. Structural — no Universe evidence required.
     */
    SUGGEST_PARENT_CAPABILITY(GroundingRule.NOT_REQUIRED);

    private final GroundingRule groundingRule;

    AssistiveQueryType(GroundingRule groundingRule) {
        this.groundingRule = groundingRule;
    }

    public GroundingRule getGroundingRule() {
        return groundingRule;
    }
}
