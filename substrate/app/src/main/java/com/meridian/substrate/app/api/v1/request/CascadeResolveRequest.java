package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * {@code POST /cascades/{id}/resolve} body (engine-spec cascade.md §6.2,
 * transitions A2/A3). {@code resolution} speaks the wire vocabulary —
 * {@code looks-good} | {@code declined} — and both outcomes are peer terminal
 * states (decline is never an error, ADR-001 non-negotiable #1).
 *
 * <p>{@code acceptedDeclineActionId} is required iff {@code declined}: it must
 * reference the latest DECLINE action of a level in the pending-decline run.
 * That is a state-machine guard, not a bean-validation shape rule, so its
 * violation surfaces as 409 {@code CASCADE_ILLEGAL_TRANSITION} (§5.2 A3) —
 * cross-field presence is deliberately not duplicated here.
 */
public record CascadeResolveRequest(
        @NotBlank String idempotencyKey,
        Integer expectedChainSeq,
        @NotBlank @Pattern(regexp = "looks-good|declined",
                message = "resolution must be 'looks-good' or 'declined'") String resolution,
        @NotBlank String resolutionNote,
        UUID acceptedDeclineActionId
) {}
