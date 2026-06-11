package com.meridian.substrate.engine.cascade.read;

import com.meridian.substrate.common.domain.Cascade;

import java.time.Instant;
import java.util.UUID;

/**
 * Resolution accountability block of {@link CascadeDetail} (engine-spec
 * cascade.md §6.1) — {@code null} on the wire while the cascade is in-flight.
 * {@code acceptedDeclineActionId} is populated iff the terminal state is
 * {@code resolved-declined} (a peer terminal outcome, never a failure — ADR-001
 * non-negotiable #1).
 */
public record CascadeResolution(
        Instant resolvedAt,
        String resolvedByLabel,
        String note,
        UUID acceptedDeclineActionId
) {

    /** @return the block, or {@code null} while the cascade is unresolved. */
    public static CascadeResolution from(Cascade cascade) {
        if (cascade.getResolvedAt() == null) {
            return null;
        }
        return new CascadeResolution(
                cascade.getResolvedAt(),
                cascade.getResolvedByLabel(),
                cascade.getResolutionNote(),
                cascade.getAcceptedDeclineActionId());
    }
}
