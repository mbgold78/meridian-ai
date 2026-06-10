package com.meridian.substrate.common.domain.enums;

/**
 * Classification categories describing patterns of manually-performed work
 * (sign-off gates, reconciliation loops, compensating controls, shadow workflows,
 * tribal-knowledge dependencies).
 *
 * Named for the kind of work it classifies (manually-performed), not for the
 * Process entity that holds it. A Process may carry this classification
 * regardless of its current automation state — a process that has been partly
 * or fully automated can still be meaningfully classified as a
 * COMPENSATING_CONTROL or SHADOW_WORKFLOW if that is what it originated as or
 * what it functionally remains.
 */
public enum ManualProcessClassification {
    SIGN_OFF,
    RECONCILIATION,
    BRIDGE,
    COMPENSATING_CONTROL,
    SHADOW_WORKFLOW,
    TRIBAL_KNOWLEDGE,
    OTHER
}
