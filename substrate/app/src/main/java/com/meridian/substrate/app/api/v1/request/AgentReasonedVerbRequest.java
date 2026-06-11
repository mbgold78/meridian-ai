package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for the reason-mandatory agent verbs — pause / kill / retire
 * (engine-spec agents.md §5.1 M6/M9/M10, §7.2). {@code reason} is DELIBERATELY
 * un-annotated: the service answers a blank reason with its structured
 * M6/M9/M10 message (ck_ase_reason discipline), not a generic
 * VALIDATION_ERROR. These verbs return HTTP 200 ALWAYS — never error-colored
 * (spec non-negotiable #2).
 */
public record AgentReasonedVerbRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        String reason,
        Integer expectedEventSeq
) {
}
