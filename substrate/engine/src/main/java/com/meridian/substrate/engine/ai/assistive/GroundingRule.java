package com.meridian.substrate.engine.ai.assistive;

/**
 * Whether an {@link AssistiveQueryType} requires its suggestions to be grounded
 * in Universe Evidence at runtime. Declared on the enum value itself (not in
 * service-side switch logic) so each new query type makes its grounding
 * contract explicit at definition time — consistent with the allowlist
 * discipline used by Generative and Validative modes.
 *
 * <p>{@link #REQUIRED} — suggestion is filtered if its evidenceIds do not
 * resolve to ≥1 in-domain Evidence record (P6 enforcement).
 * <p>{@link #NOT_REQUIRED} — structural query; suggestion is accepted with or
 * without evidence.
 */
public enum GroundingRule {
    REQUIRED,
    NOT_REQUIRED
}
