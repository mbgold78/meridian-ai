package com.meridian.substrate.engine.cascade.read;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only advisory derived from {@code SIBLING_DECLINE} notification rows
 * anchored to this cascade (engine-spec cascade.md §6.1; ADR D2): a level in a
 * grouped sibling cascade declined — zero state coupling, informational only.
 * EXTENSION POINT(cascade-6): cross-cascade structural decline settlement is
 * advisory-only; an operator-cited decline_reference link is the designed next
 * step if demanded (spec §13.6).
 */
public record SiblingDeclineAdvisory(
        UUID decliningCascadeId,
        Integer depth,
        Instant declinedAt,
        String reasoningExcerpt
) {
}
