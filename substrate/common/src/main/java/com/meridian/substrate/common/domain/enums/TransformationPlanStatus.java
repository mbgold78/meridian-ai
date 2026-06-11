package com.meridian.substrate.common.domain.enums;

/**
 * Transformation-plan lifecycle vocabulary (doc-06; engine-spec diagnostic.md
 * §3.3 — D5 thin slice, ADR-004 D13). The value SET is enforced by the type;
 * the state-machine TRANSITIONS are deliberately unenforced in v1 — the
 * planning engine proper is S4 future work (spec §15.9,
 * EXTENSION POINT(diagnostic-9)).
 */
public enum TransformationPlanStatus {
    DRAFT,
    UNDER_REVIEW,
    APPROVED,
    EXECUTING,
    COMPLETE,
    ARCHIVED
}
