package com.meridian.substrate.common.domain.enums;

/**
 * Executable rule family of a contract monitor (engine-spec contracts.md
 * §2.1, §5.3; ADR-002 D10/D11). {@code UNSTRUCTURED} is the declared
 * degradation: mechanism prose that does not compile to a structured rule —
 * it flags a recurring {@code HUMAN_VERIFY_DUE} signal on its cadence so a
 * monitor can be weak but never silently inert.
 */
public enum MonitorRuleType {
    ATTESTATION_CADENCE,
    METRIC_THRESHOLD,
    MCP_PROBE,
    UNSTRUCTURED
}
