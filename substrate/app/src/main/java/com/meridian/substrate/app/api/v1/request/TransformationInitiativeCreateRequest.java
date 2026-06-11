package com.meridian.substrate.app.api.v1.request;

import com.meridian.substrate.common.domain.enums.InitiativeComplexity;
import com.meridian.substrate.common.domain.enums.InitiativeStatus;
import com.meridian.substrate.common.domain.enums.InitiativeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/transformation/plans/{id}/initiatives} body
 * (engine-spec diagnostic.md §10.1 — D5 thin-slice CRUD, V8). No
 * wave-sequencing or prerequisite-graph validation exists in v1 — those are
 * planning-engine semantics (spec §15.9, EXTENSION POINT(diagnostic-9));
 * {@code prerequisites}/{@code evidenceIds} persist as unvalidated JSON UUID
 * arrays (soft refs, app-enforced convention).
 */
public record TransformationInitiativeCreateRequest(
        @NotBlank(message = "name is required")
        String name,
        String description,
        @NotNull(message = "initiativeType is required")
        InitiativeType initiativeType,
        InitiativeStatus status,
        UUID targetCapabilityId,
        UUID targetProcessId,
        UUID targetSystemId,
        Integer wave,
        String ownerName,
        Integer estimatedMonths,
        InitiativeComplexity complexity,
        Long annualSavingsUsd,
        List<UUID> prerequisites,
        List<UUID> evidenceIds,
        @Min(value = 0, message = "progressPct must be between 0 and 100")
        @Max(value = 100, message = "progressPct must be between 0 and 100")
        Integer progressPct,
        String progressNote
) {}
