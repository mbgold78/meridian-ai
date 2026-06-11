package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/{idOrKey}/confirm-control} body (engine-spec agents.md
 * §5.1 M16/§7.2; ADR-003 D6): human-attested confirmation that a
 * RECORDED_INTENT control actually took effect — evidence refs MANDATORY
 * (service-checked); distinct from runtime acknowledgment, never auto-derived
 * from sighting absence.
 */
public record AgentConfirmControlRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        JsonNode evidenceRefs,
        String note,
        Integer expectedEventSeq
) {
}
