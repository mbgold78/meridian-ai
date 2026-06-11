package com.meridian.substrate.common.domain.enums;

/**
 * Origin of a breach detection (engine-spec contracts.md §2.1, §2.4):
 * a monitor sweep run, a human attestation claiming MISSED, or a gateway
 * FLAG escalated by an operator.
 */
public enum BreachDetectedBy {
    MONITOR,
    HUMAN_ATTESTATION,
    GATEWAY_FLAG
}
