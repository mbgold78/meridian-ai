package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /breaches/{id}/waive} body (engine-spec contracts.md
 * §7.2/§2.4): ACKNOWLEDGED → WAIVED; {@code rationale} MANDATORY. Waiver is
 * HUMAN-ONLY (ADR D16 built-in non-removable guard — non-human actors get
 * 403) and is mirrored into the ADR D14 hash chain.
 */
public record BreachWaiveRequest(@NotBlank String rationale) {}
