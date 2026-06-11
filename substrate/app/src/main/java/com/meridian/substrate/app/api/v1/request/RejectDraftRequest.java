package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/v1/ai/generative/reject} body (engine-spec diagnostic.md
 * §5.3/§10.1): ONE assertion id per call (no bulk verb). The rationale is
 * service-enforced (400 {@code RATIONALE_REQUIRED}) — see
 * {@link RejectFindingRequest} for why it is not bean-validated here.
 */
public record RejectDraftRequest(
        @NotNull(message = "assertionId is required")
        UUID assertionId,
        String rationale,
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {}
