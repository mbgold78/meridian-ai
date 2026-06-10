package com.meridian.substrate.app.api.v1.request;

import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CapabilityCreateRequest(
        @NotNull UUID domainId,
        UUID parentCapabilityId,
        @NotBlank String name,
        @NotBlank String displayName,
        String description,
        Integer level,
        CapabilityStatus status,
        Boolean aiGenerated
) {}
