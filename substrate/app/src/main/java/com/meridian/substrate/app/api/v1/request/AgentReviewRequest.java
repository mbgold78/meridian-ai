package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/{idOrKey}/reviews} body (engine-spec agents.md §7.2;
 * ADR-003 D10): records a compliance-review REQUEST — reviewer identity,
 * decision, rationale and evidence land at decide time and NOTHING else
 * (review-content semantics are UNKNOWN, spec §17 #3).
 */
public record AgentReviewRequest(
        String reviewKind,                      // wire: promotion | periodic | incident
        @NotBlank @Size(max = 255) String requestedByLabel
) {
}
