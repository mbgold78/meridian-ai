package com.meridian.substrate.engine.ai.orchestration;

import java.util.UUID;

/**
 * Carries the originating designer surface and optional active-entity context
 * for an AI orchestration call. Mode services read this to scope prompts, tool
 * selection, and grounding queries.
 *
 * <p>{@code entityType} and {@code entityId} are nullable — present when the
 * user is editing or focused on a specific entity (e.g. a Capability draft in
 * the Capability Designer), absent for surface-level queries (e.g. a free-form
 * Cmd+K analyst question).
 */
public record SurfaceContext(Surface surface, String entityType, UUID entityId) {

    /** Default surface for the existing Ambient Chat Bar / analyst path. */
    public static SurfaceContext analystQuery() {
        return new SurfaceContext(Surface.ANALYST_QUERY, null, null);
    }

    /** Surface-level invocation with no specific entity in focus. */
    public static SurfaceContext of(Surface surface) {
        return new SurfaceContext(surface, null, null);
    }

    /** Invocation scoped to a specific entity the user is editing or viewing. */
    public static SurfaceContext forEntity(Surface surface, String entityType, UUID entityId) {
        return new SurfaceContext(surface, entityType, entityId);
    }
}
