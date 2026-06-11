package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/v1/initiatives/{id}/progress} body (engine-spec
 * diagnostic.md §10.1/§12.5): {@code progressPct (0..100)!, progressNote?}.
 * Progress is operator-maintained — the only computed rendering rule anywhere
 * in D5 is {@code status=COMPLETE ⇒ 100}; deriving progress from activity is
 * the named fabricated-analytics failure mode (ADR-004 D13).
 */
public record InitiativeProgressRequest(
        @NotNull(message = "progressPct is required")
        @Min(value = 0, message = "progressPct must be between 0 and 100")
        @Max(value = 100, message = "progressPct must be between 0 and 100")
        Integer progressPct,
        String progressNote
) {}
