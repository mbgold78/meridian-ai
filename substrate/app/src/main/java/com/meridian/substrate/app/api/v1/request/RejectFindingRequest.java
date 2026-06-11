package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/findings/{id}/reject} body (engine-spec diagnostic.md
 * §9/§10.1). The rationale is deliberately NOT bean-validated here: it is
 * SERVICE-enforced (400 {@code RATIONALE_REQUIRED}, field {@code rationale})
 * so the rule holds for every caller of {@code CurationService}, and the
 * spec-named error code reaches the envelope instead of a generic
 * {@code VALIDATION_ERROR}.
 */
public record RejectFindingRequest(
        String rationale,
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {}
