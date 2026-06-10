package com.meridian.substrate.engine.ai.validative;

import java.util.List;

/**
 * Response from {@link ValidativeModeService#validate}.
 *
 * <p>{@code findings} is empty when every candidate was filtered (by the
 * type/severity allowlists, the missing required-fields check, or the
 * differential grounding rule); in that case {@code explanation} carries a
 * human-readable reason. When at least one candidate survives,
 * {@code explanation} is null.
 *
 * <p>{@code filteredCount} reports how many candidates the LLM produced that
 * were dropped — useful UI signal ("AI raised 8 findings, 3 were filtered").
 */
public record ValidateResponse(
        List<FindingCandidate> findings,
        int filteredCount,
        String explanation
) {}
