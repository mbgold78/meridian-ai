package com.meridian.substrate.common.domain.enums;

/**
 * Curation lifecycle projection on a {@code Finding} (engine-spec
 * diagnostic.md §3.1/§9; ADR-004 D4/D10). A {@code NULL} column — deliberately
 * representable only by a null field, never an enum value — means "not subject
 * to curation" (operator/fixture rows; DiagnosticSeeder rows stay NULL so demo
 * behavior is unchanged).
 *
 * <p>Truth lives in the append-only {@code ai_curation_events} table; this
 * enum is the last-event-wins projection. REJECTED/SUPERSEDED are product
 * outcomes, never error vocabulary (spec non-negotiable #3): a rejected row
 * stays, default list queries exclude it, {@code ?includeRejected=true}
 * restores it, and ACCEPT-after-REJECT is legal recourse.
 */
public enum CurationStatus {
    PENDING_REVIEW,
    ACCEPTED,
    REJECTED,
    SUPERSEDED
}
