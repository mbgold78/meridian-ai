package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * {@code POST /cascade-drafts/{id}/issue} body (engine-spec cascade.md §6.3;
 * §5.2 A1). The mandatory {@code idempotencyKey} anchors issuance idempotency
 * on the draft row (ADR D8): re-issue of an ISSUED draft returns the existing
 * cascade (200) instead of creating a duplicate.
 *
 * <p>The draft itself must carry a non-blank critique + acceptance criteria —
 * that is the A1 state-machine guard (409 {@code CASCADE_ILLEGAL_TRANSITION}),
 * not a bean-validation shape rule, so it is deliberately not duplicated here.
 * {@code originatorRefinedCritique} defaults to the original critique (L0's
 * refined one-liner, §5.2 ISSUE mechanics).
 */
public record CascadeDraftIssueRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String level1OwnerLabel,
        UUID level1OwnerMemberId,
        String originatorRefinedCritique
) {}
