package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /cascades/{id}/levels/{depth}/decline} body (engine-spec
 * cascade.md §6.2, transitions L3 first-touch / L4 endorse-decline).
 * A legitimate decline returns <b>200 ALWAYS</b> — decline is never an error
 * (ADR-001 non-negotiable #1); 403/404/409 arise only from forbidden actors,
 * unknown ids, stale fencing or illegal transitions. The documented
 * {@code declineReasoning} is mandatory (invariant I4).
 */
public record CascadeDeclineRequest(
        @NotBlank String idempotencyKey,
        Integer expectedChainSeq,
        @NotBlank String declineReasoning
) {}
