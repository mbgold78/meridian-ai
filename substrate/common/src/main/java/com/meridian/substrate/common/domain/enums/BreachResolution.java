package com.meridian.substrate.common.domain.enums;

/**
 * Terminal resolution of a breach (engine-spec contracts.md §2.4):
 * REMEDIATED requires {@code remediation_evidence_refs}; WAIVED requires a
 * human actor + mandatory rationale (service-enforced, ADR D16).
 */
public enum BreachResolution {
    REMEDIATED,
    WAIVED
}
