package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /contracts/{idOrKey}/decline} body (engine-spec contracts.md
 * §6.1/§7.2 {@code invoke_decline}): cites a pre-agreed decline trigger
 * belonging to the contract; {@code reasoning} mandatory; {@code partyId}
 * optional (checked only against trigger {@code invocable_by_party_id};
 * party identity is advisory pre-Phase-5, spec §15 #12).
 *
 * <p>DECLINE is a legitimate first-class terminal resolution: success is
 * <b>200</b> with the full detail (status DECLINED), NEVER an error code
 * (ADR-002 D6; port-map trap #8). 409 arises only for
 * {@code INVALID_CONTRACT_STATE} (and {@code CONTRACT_DENIAL
 * REVIEW_BOUNDARY} once the 3c gateway enforces non-null boundaries).
 */
public record ContractDeclineRequest(
        @NotNull UUID declineTriggerId,
        @NotBlank String reasoning,
        UUID partyId
) {}
