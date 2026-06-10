package com.meridian.substrate.common.domain.enums;

/**
 * Execution mode for a single ProcessStep — distinct from {@link ExecutionMode},
 * which describes a CanonicalEntity and uses SYSTEM/MANUAL/HYBRID. See
 * docs/phase-handoffs.md for the drift note.
 */
// TODO(phase-2): reconcile ProcessStepExecutionMode with ExecutionMode when
// CanonicalEntity is repositioned as a Map-phase lens (doc 11).
public enum ProcessStepExecutionMode {
    MANUAL,
    SEMI_AUTOMATED,
    AUTOMATED
}
