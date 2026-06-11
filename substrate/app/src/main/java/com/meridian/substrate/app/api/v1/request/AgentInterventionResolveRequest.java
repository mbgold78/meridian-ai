package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agents/interventions/{id}/resolve} body (engine-spec agents.md
 * §5.1 M13/§7.2): resolution + note MANDATORY; {@code resolution=retired}
 * records intent only — the kill/retire verb is a separate attributed event.
 */
public record AgentInterventionResolveRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        String resolution,                      // wire: resumed | reconfigured | demoted-to-shadow | retired | no-action
        String resolutionNote
) {
}
