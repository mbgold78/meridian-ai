package com.meridian.substrate.app.api.v1.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /breaches/{id}/remediate} body (engine-spec contracts.md
 * §7.2/§2.4): ACKNOWLEDGED → REMEDIATED; {@code evidenceRefs} (JSON array of
 * Evidence/DocumentRegistry ids) is MANDATORY — service-enforced non-empty;
 * existence is soft-checked warn-don't-fail. Sets the commitment back to
 * COMPLIANT.
 */
public record BreachRemediateRequest(@NotNull JsonNode evidenceRefs) {}
