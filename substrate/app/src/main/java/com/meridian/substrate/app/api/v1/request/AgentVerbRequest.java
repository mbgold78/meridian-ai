package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for the parameterless agent verbs — resume / promote / promote-request
 * / intervention acknowledge (engine-spec agents.md §7.2): every write body
 * REQUIRES {@code idempotencyKey}; {@code expectedEventSeq} is the optional
 * fencing token (stale ⇒ 409 {@code AGENT_STALE_STATE} with the current
 * detail in the envelope {@code data}).
 */
public record AgentVerbRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        Integer expectedEventSeq
) {
}
