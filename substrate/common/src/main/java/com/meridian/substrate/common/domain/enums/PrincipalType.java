package com.meridian.substrate.common.domain.enums;

/**
 * Type of the principal behind a governed action (engine-spec contracts.md
 * §2.1, §4.1). Fail posture is principal-typed (ADR D5): evaluation failure
 * ⇒ DENY for AGENT/SERVICE, ALLOW_WITH_FLAG for HUMAN.
 */
public enum PrincipalType {
    HUMAN,
    AGENT,
    SERVICE
}
