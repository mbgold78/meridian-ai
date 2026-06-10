package com.meridian.substrate.common.domain.enums;

/**
 * Classification of findings detected during domain analysis.
 */
public enum FindingType {
    DUPLICATION,
    COVERAGE_GAP,
    STALE_ASSESSMENT,
    ROLE_OVERLAP,
    INTEGRATION_BROKEN,
    DATA_QUALITY,
    MANUAL_PROCESS_DEPENDENCY,
    UNDOCUMENTED_WORKFLOW,
    COMPENSATING_CONTROL_RISK,
    TRIBAL_KNOWLEDGE_RISK,
    SHADOW_WORKFLOW_DETECTED,
    MANUAL_RECONCILIATION_VOLUME
}
