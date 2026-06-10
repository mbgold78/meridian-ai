package com.meridian.substrate.engine.ai.assistive;

import java.util.List;
import java.util.UUID;

/**
 * A single inline suggestion returned from {@link AssistiveModeService}.
 *
 * <p>{@code dismissible} is always {@code true} — the user must always be
 * able to ignore an assistive suggestion. The service sets this field
 * regardless of what the LLM emits, so a malformed or adversarial response
 * cannot mark a suggestion non-dismissible.
 *
 * <p>{@code evidenceIds} is non-empty for query types whose
 * {@link AssistiveQueryType#getGroundingRule()} is {@link GroundingRule#REQUIRED};
 * may be empty for structural query types (name-collision, parent suggestion).
 */
public record Suggestion(
        String value,
        Float confidence,
        boolean dismissible,
        List<UUID> evidenceIds,
        UUID assertionId
) {}
