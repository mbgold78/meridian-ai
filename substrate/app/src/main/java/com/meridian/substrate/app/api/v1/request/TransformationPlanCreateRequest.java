package com.meridian.substrate.app.api.v1.request;

import com.meridian.substrate.common.domain.enums.ReductionMode;
import com.meridian.substrate.common.domain.enums.TransformationPlanStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/v1/transformation/plans} body (engine-spec diagnostic.md
 * §10.1 — D5 thin-slice CRUD, V8). Status defaults to DRAFT; transitions are
 * unenforced in v1 (spec §15.9, EXTENSION POINT(diagnostic-9)).
 */
public record TransformationPlanCreateRequest(
        @NotNull(message = "domainId is required")
        UUID domainId,
        @NotBlank(message = "name is required")
        String name,
        String description,
        TransformationPlanStatus status,
        Integer targetHorizonMonths,
        ReductionMode reductionMode
) {}
