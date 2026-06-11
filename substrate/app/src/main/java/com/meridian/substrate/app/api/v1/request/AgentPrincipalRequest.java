package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/{idOrKey}/principal} body (engine-spec agents.md §5.1
 * M18/§7.2): binds {@code Principal(AGENT, …)} to the registration — unique
 * when present (gateway resolution correctness). Spoofable until Phase-5 OIDC
 * (S9 accepted; EXTENSION POINT(agent-17) lives at the service).
 */
public record AgentPrincipalRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Size(max = 255) String mcpPrincipalId,
        Integer expectedEventSeq
) {
}
