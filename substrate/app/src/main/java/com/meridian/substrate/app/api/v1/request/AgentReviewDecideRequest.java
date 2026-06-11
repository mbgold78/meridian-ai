package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /agents/reviews/{id}/decide} body (engine-spec agents.md §5.1
 * M5/§7.2; ADR-003 D10): rationale is mandatory at decision; REJECTED renders
 * info/neutral and is re-requestable — never error-colored.
 */
public record AgentReviewDecideRequest(
        String decision,                        // wire: approved | rejected | needs-more-time
        @NotBlank String rationale,
        String reviewerLabel,
        java.util.UUID reviewerMemberId,
        JsonNode evidenceRefs
) {
}
