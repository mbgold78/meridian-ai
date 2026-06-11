package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/v1/ai/generative/commit} body (engine-spec diagnostic.md
 * §5.3/§10.1): ONE assertion id per call — there is no bulk-accept verb,
 * structurally (P6/doc-07; test plan #8). {@code overrides} are the optional
 * human edits applied AFTER the asserted fields; the applied delta is
 * recorded on the ACCEPT event as provenance.
 */
public record CommitDraftRequest(
        @NotNull(message = "assertionId is required")
        UUID assertionId,
        JsonNode overrides,
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {}
