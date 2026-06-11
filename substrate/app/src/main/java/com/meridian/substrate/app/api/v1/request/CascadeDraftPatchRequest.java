package com.meridian.substrate.app.api.v1.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code PATCH /cascade-drafts/{id}} body (engine-spec cascade.md §6.3) —
 * partial update of the create fields, only while the draft is OPEN (409
 * otherwise). Absent ({@code null}) fields are left untouched.
 */
public record CascadeDraftPatchRequest(
        @Pattern(regexp = "free-form|imperative|ai-suggested",
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
