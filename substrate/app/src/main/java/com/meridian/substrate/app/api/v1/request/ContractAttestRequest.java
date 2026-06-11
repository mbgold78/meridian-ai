package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /commitments/{id}/attest} body (engine-spec contracts.md
 * §7.2/§5.5): idempotent upsert on {@code (commitment_id, period_key)}
 * (ADR D13) with claim side effects per §5.3; {@code complianceClaim} is
 * {@code ON_TRACK | COMPLETE | AT_RISK | MISSED} (parsed case-insensitively;
 * MISSED auto-opens a breach). {@code evidenceRefs} is a JSON array of
 * Evidence/DocumentRegistry ids — soft refs, validated warn-don't-fail.
 */
public record ContractAttestRequest(
        @NotBlank String periodKey,
        @NotBlank String complianceClaim,
        String statement,
        JsonNode evidenceRefs
) {}
