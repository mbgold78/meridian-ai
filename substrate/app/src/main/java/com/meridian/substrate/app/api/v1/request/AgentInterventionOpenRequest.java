package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/{idOrKey}/interventions} body (engine-spec agents.md
 * §5.1 M11/§7.2): human-opened attention overlay — the agent's mode is
 * UNCHANGED (an in-intervention agent typically keeps operating; ADR-003 D4).
 */
public record AgentInterventionOpenRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        String reason,
        JsonNode evidenceRefs,
        Integer expectedEventSeq
) {
}
