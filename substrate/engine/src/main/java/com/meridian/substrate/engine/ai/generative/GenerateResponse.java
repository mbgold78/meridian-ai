package com.meridian.substrate.engine.ai.generative;

import java.util.List;

/**
 * Response from {@link GenerativeModeService#generate}.
 *
 * <p>{@code drafts} is empty when every candidate was filtered for lack of
 * grounding evidence; in that case {@code explanation} carries a human-readable
 * reason (per spec: "if all suggestions lack grounding, returns empty result
 * with explanation"). When at least one grounded draft survives,
 * {@code explanation} is null.
 *
 * <p>{@code filteredCount} reports how many candidates the LLM produced that
 * were dropped by the grounding filter — useful signal for the UI ("AI
 * suggested 5 items, 3 were filtered for lack of evidence").
 */
public record GenerateResponse(
        List<DraftEntity> drafts,
        int filteredCount,
        String explanation
) {}
