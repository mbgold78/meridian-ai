package com.meridian.substrate.engine.cascade.read;

import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /cascades/stats} body (engine-spec cascade.md §6.1) — engine-
 * computed dashboard statistics (fixture seam S6).
 *
 * <p>{@code byState} carries all three aggregate states (kebab-case keys,
 * zero-filled). {@code bySignal} carries the in-flight attention signals only
 * ({@code decline-pending}, {@code escalating}, {@code absorbed}) exactly as
 * the §6.1 sample shapes it — terminal outcomes are already counted in
 * {@code byState}. {@code overdueCount} is the overlay-flag count (never a
 * signal, spec §2).
 */
public record CascadeStats(
        UUID domainId,
        long total,
        Map<String, Long> byState,
        Map<String, Long> bySignal,
        long overdueCount,
        long actionsLast7Days
) {
}
