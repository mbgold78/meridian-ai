package com.meridian.substrate.app.api.v1.request;

/**
 * Payload for {@code PATCH /api/v1/capabilities/{id}/sequence} — sets the
 * 0-based target position within the current sibling group. The service
 * clamps out-of-range values ({@code -1}, beyond the last sibling) to the
 * valid bounds rather than rejecting, so the caller doesn't need to fetch
 * the sibling count first.
 *
 * <p>Dedicated DTO + endpoint for the same reason the reparent DTO exists:
 * absent {@code newSequence} in a partial PATCH would be indistinguishable
 * from an explicit {@code 0}, silently front-loading a capability in its
 * sibling group.
 */
public record CapabilitySequenceRequest(
        int newSequence
) {}
