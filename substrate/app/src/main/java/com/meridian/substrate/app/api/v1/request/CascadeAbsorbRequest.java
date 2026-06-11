package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /cascades/{id}/levels/{depth}/absorb} body (engine-spec
 * cascade.md §6.2, transitions L2/L6): the owner takes the work; the
 * refined-critique translation is required (invariant I4).
 */
public record CascadeAbsorbRequest(
        @NotBlank String idempotencyKey,
        Integer expectedChainSeq,
        @NotBlank String refinedCritique
) {}
