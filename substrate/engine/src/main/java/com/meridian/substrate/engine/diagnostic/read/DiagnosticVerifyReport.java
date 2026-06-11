package com.meridian.substrate.engine.diagnostic.read;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chain-integrity sweep over every {@code aiGenerated=true} finding of a
 * domain (engine-spec diagnostic.md §6.1; ADR-004 D5) — the diagnostic twin
 * of {@code /decision-log/verify}. Broken chains and fixture-grounded chains
 * ({@code extractor='seed'}) are DISTINCT classes (membership is independent:
 * a seed chain is fixture-grounded AND reports its missing ingest-run hop).
 *
 * <p>{@code total=0} against DemoPack (every seeded finding is
 * {@code aiGenerated=false}) is the honest pre-generation answer, not an
 * error. {@code groundingModes} counts the persisted honesty marker per
 * finding ({@code EXISTENCE_ONLY} for all v1 AI findings — spec §6.2); a
 * finding without one is bucketed {@code UNSPECIFIED}, never silently
 * dropped (no-silent-zero-yield applied to the sweep).
 */
public record DiagnosticVerifyReport(
        UUID domainId,
        int total,
        int chainComplete,
        List<BrokenChain> brokenChains,
        List<UUID> fixtureGrounded,
        Map<String, Integer> groundingModes
) {

    /** One broken walk: the finding and its first named missing hop. */
    public record BrokenChain(UUID findingId, String missingHop) {}
}
