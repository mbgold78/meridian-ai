package com.meridian.substrate.common.domain.enums;

/**
 * Transformation-initiative status vocabulary (doc-06; engine-spec
 * diagnostic.md §3.3). {@code DEFERRED}/{@code CANCELLED} are product
 * outcomes, never error vocabulary. The ONLY computed rendering rule anywhere
 * in D5 keys on this enum: {@code COMPLETE => renderedProgressPct = 100}
 * (spec §12.5) — everything else is operator-maintained, never derived from
 * activity (fabricated analytics is the named demo-pressure failure mode,
 * ADR-004 D13).
 */
public enum InitiativeStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETE,
    DEFERRED,
    CANCELLED
}
