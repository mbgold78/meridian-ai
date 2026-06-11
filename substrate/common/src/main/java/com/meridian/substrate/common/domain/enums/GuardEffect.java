package com.meridian.substrate.common.domain.enums;

/**
 * Effect of a matched clause guard (engine-spec contracts.md §2.1, §4.3).
 *
 * <p>Declaration order IS the evaluation precedence: DENY &gt;
 * REQUIRE_ATTESTATION &gt; FLAG (spec §4.3 — compare with
 * {@link #ordinal()} / enum order, do not reorder).
 */
public enum GuardEffect {
    DENY,
    REQUIRE_ATTESTATION,
    FLAG
}
