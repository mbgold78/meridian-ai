package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * {@code POST /cascades/{id}/levels/{depth}/cascade} body (engine-spec
 * cascade.md §6.2, transition L1). Operator-grade and shape-neutral —
 * {@code idempotencyKey} is mandatory on every state-changing request
 * (ADR-001 D8); {@code expectedChainSeq} is the optional fencing token
 * (mismatch → 409 {@code CASCADE_STALE_STATE} with current detail in
 * {@code data}); {@code nextOwnerMemberId} is a nullable soft member ref
 * beside the required label (ADR D11).
 */
public record CascadeCascadeRequest(
        @NotBlank String idempotencyKey,
        Integer expectedChainSeq,
        @NotBlank String refinedCritique,
        UUID nextOwnerMemberId,
        @NotBlank String nextOwnerLabel
) {}
