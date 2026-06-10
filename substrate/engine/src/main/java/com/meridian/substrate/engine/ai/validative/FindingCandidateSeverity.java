package com.meridian.substrate.engine.ai.validative;

/**
 * Allowlisted severities for {@link FindingCandidate}. The set is closed and
 * matches the Phase 2A spec exactly — any value the LLM emits outside this
 * enum is rejected by {@link ValidativeModeService}, never passed through.
 *
 * <p>Distinct from {@code com.meridian.substrate.common.domain.enums.Severity}
 * (which has {@code LOW/MEDIUM/HIGH/CRITICAL}) because the validative spec
 * uses {@code INFO} where the existing enum has {@code LOW} — the semantics
 * differ ({@code INFO} = "FYI observation", {@code LOW} = "low-severity issue
 * exists"). The Phase 2B commit step that materializes a candidate as a real
 * {@code Finding} entity is responsible for the mapping if needed.
 */
public enum FindingCandidateSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    INFO
}
