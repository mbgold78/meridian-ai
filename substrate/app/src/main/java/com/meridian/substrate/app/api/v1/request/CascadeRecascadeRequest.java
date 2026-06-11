package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * {@code POST /cascades/{id}/levels/{depth}/recascade} body (engine-spec
 * cascade.md §6.2, transition L5): a revised refinement reactivates the
 * declined level below as a new round; the next owner may differ from the
 * prior round's owner (nullable soft member ref + required label, ADR D11).
 */
public record CascadeRecascadeRequest(
        @NotBlank String idempotencyKey,
        Integer expectedChainSeq,
        @NotBlank String refinedCritique,
        UUID nextOwnerMemberId,
        @NotBlank String nextOwnerLabel
) {}
