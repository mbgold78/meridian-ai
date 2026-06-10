package com.meridian.substrate.engine.ai.orchestration;

/**
 * The designer surface from which an AI invocation originates. Used by
 * {@link SurfaceContext} to scope prompt construction and tool selection.
 *
 * <p>{@link #ANALYST_QUERY} represents the Cmd+K analyst panel; ambient
 * chat-bar invocations route through this surface as the backwards-compatible
 * default.
 */
public enum Surface {
    CAPABILITY_DESIGNER,
    ENVIRONMENT_DESIGNER,
    PLAN_DESIGNER,
    ANALYST_QUERY
}
