package com.meridian.substrate.common.domain.enums;

/**
 * Transformation-plan reduction-mode lens parameter (doc-06; engine-spec
 * diagnostic.md §3.3). The rationalization LENS that consumes it is NOT built
 * in v1 (spec §15.9, EXTENSION POINT(diagnostic-9)) — the column carries
 * operator intent only.
 */
public enum ReductionMode {
    AGGRESSIVE,
    BALANCED,
    CONSERVATIVE
}
