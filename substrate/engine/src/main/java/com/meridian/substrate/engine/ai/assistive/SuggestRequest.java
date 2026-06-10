package com.meridian.substrate.engine.ai.assistive;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.engine.ai.orchestration.Surface;

import java.util.UUID;

/**
 * Request for {@link AssistiveModeService#suggest}. {@code queryType} is a
 * string (not the enum) at the API boundary so unknown types can be rejected
 * gracefully rather than failing JSON deserialization with a 4xx —
 * {@code AssistiveModeService} maps the string to {@link AssistiveQueryType}
 * and returns a "not implemented" response for anything outside the allowlist.
 *
 * <p>{@code queryContext} is open-ended — the frontend includes whatever is
 * relevant for the query type (current text being edited, sibling capability
 * names, capability tree summary, etc.). The service routes per query type
 * and uses what it needs.
 */
public record SuggestRequest(
        UUID domainId,
        Surface surface,
        String entityType,
        UUID entityId,
        String queryType,
        JsonNode queryContext
) {}
