package com.meridian.substrate.engine.ai.assistive;

import java.util.List;

/**
 * Response from {@link AssistiveModeService#suggest}.
 *
 * <p>{@code suggestions} is empty when no candidate survived the allowlist /
 * grounding filters, OR when the LLM legitimately found nothing to suggest
 * (e.g. {@code DETECT_NAME_COLLISION} with no actual collisions). In either
 * case {@code explanation} carries a brief reason — distinguishing
 * "we filtered everything" from "the answer really is nothing" matters for
 * the UI's status text.
 */
public record SuggestResponse(
        List<Suggestion> suggestions,
        int filteredCount,
        String explanation
) {}
