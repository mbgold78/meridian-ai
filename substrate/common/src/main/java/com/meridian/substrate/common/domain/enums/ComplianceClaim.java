package com.meridian.substrate.common.domain.enums;

/**
 * The claim carried by a contract attestation (engine-spec contracts.md §2.1,
 * §5.3): {@code ON_TRACK}/{@code COMPLETE} set the commitment COMPLIANT for
 * the period; {@code AT_RISK} sets AT_RISK without a breach; {@code MISSED}
 * auto-opens a breach ({@code detected_by=HUMAN_ATTESTATION}).
 */
public enum ComplianceClaim {
    ON_TRACK,
    COMPLETE,
    AT_RISK,
    MISSED
}
