package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/findings/{id}/accept} body (engine-spec diagnostic.md
 * §9/§10.1): rationale is OPTIONAL on accept; the idempotency key is
 * mandatory (replay returns the original result, no new event).
 */
public record AcceptFindingRequest(
        String rationale,
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {}
