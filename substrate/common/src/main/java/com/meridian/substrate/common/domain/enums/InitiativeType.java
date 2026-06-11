package com.meridian.substrate.common.domain.enums;

/**
 * Transformation-initiative type vocabulary (doc-06; engine-spec
 * diagnostic.md §3.3 — D5 thin slice, ADR-004 D13).
 */
public enum InitiativeType {
    AGENT_DEPLOY,
    SYSTEM_RETIRE,
    PROCESS_REDESIGN,
    ORG_CHANGE,
    REGULATORY,
    CAPABILITY_ADD,
    CAPABILITY_RETIRE
}
