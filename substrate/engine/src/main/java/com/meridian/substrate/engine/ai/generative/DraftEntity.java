package com.meridian.substrate.engine.ai.generative;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * A single AI-proposed entity returned from {@link GenerativeModeService}.
 * Drafts are NOT persisted as the underlying entity (Capability, Process, etc.) —
 * the {@code assertionId} points at the {@code Assertion} record that captures
 * the proposal; downstream commit code (Phase 2B) decides whether to materialize
 * the draft as a real entity.
 *
 * <p>{@code evidenceIds} is guaranteed non-empty by the service's grounding
 * filter — drafts without grounding evidence are filtered before this DTO is
 * constructed.
 */
public record DraftEntity(
        String entityType,
        JsonNode proposedFields,
        List<UUID> evidenceIds,
        UUID assertionId
) {}
