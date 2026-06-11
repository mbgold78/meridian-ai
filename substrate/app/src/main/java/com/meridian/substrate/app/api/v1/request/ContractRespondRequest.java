package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /contracts/{idOrKey}/respond} body (engine-spec contracts.md
 * §7.2): records the party's immutable response at proposal stage.
 * {@code response} is {@code ACCEPTED | DECLINED} (parsed case-insensitively);
 * {@code declineReasoning} is service-enforced mandatory iff DECLINED;
 * {@code declineTriggerId} is an optional citation at proposal stage. A
 * legitimate decline returns <b>200 ALWAYS</b> (port-map trap #8); duplicate
 * identical responses are idempotent 200s, conflicting ones 409
 * {@code RESPONSE_CONFLICT} (§10.2).
 */
public record ContractRespondRequest(
        @NotNull UUID partyId,
        @NotBlank String response,
        UUID declineTriggerId,
        String declineReasoning
) {}
