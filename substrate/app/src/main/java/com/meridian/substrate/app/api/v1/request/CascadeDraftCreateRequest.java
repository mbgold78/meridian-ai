package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /cascade-drafts} body (engine-spec cascade.md §6.3). Validation
 * annotations live HERE only, per the port rule. {@code authoringMode} speaks
 * the kebab-case wire vocabulary (spec §2); FREE_FORM + IMPERATIVE are fully
 * usable now, AI_SUGGESTED persists for the §7 suggest lane.
 *
 * <p>{@code responseSlaDays}: absent → the engagement default is applied (and
 * stored resolved) at issue time; explicit {@code 0} disables the SLA for the
 * issued cascade (ADR D5).
 */
public record CascadeDraftCreateRequest(
        @NotNull UUID domainId,
        @NotBlank @Pattern(regexp = "free-form|imperative|ai-suggested",
                message = "authoringMode must be 'free-form', 'imperative' or 'ai-suggested'")
        String authoringMode,
        String originalCritique,
        String acceptanceCriteria,
        @Size(max = 500) String summary,
        UUID affectedDomainId,
        UUID originFindingId,
        UUID cascadeGroupId,
        @PositiveOrZero Integer responseSlaDays
) {}
