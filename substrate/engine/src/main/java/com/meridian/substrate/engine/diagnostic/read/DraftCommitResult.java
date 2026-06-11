package com.meridian.substrate.engine.diagnostic.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.Capability;

/**
 * Outcome of {@code DraftCommitService.commitDraft} (engine-spec
 * diagnostic.md §5.3): the materialized {@code Capability(status=DRAFT,
 * aiGenerated=true)} plus the human-edit delta recorded on the ACCEPT event
 * (the human edit is itself provenance — ADR-004 D9).
 *
 * <p>{@code replayed=true} means the draft was ALREADY accepted — the
 * existing capability is returned idempotently (HTTP 200, not 201), no new
 * entity and no new event.
 */
public record DraftCommitResult(
        Capability capability,
        JsonNode overriddenFields,
        boolean replayed
) {}
