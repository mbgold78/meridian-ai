package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/{idOrKey}/attest-liveness} body (engine-spec agents.md
 * §5.1 M15/§7.2; ADR-003 D11): a HUMAN "I verified this agent is running" —
 * the honest-liveness lane for ATTEST_ONLY agents; attestation is evidence,
 * row recency never is (invariant I7).
 */
public record AgentAttestLivenessRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Size(max = 500) String statement,
        JsonNode evidenceRefs,
        Integer expectedEventSeq
) {
}
