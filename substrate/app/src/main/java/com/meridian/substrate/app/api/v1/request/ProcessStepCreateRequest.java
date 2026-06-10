package com.meridian.substrate.app.api.v1.request;

import com.meridian.substrate.common.domain.enums.ProcessStepExecutionMode;
import com.meridian.substrate.common.domain.enums.StepType;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ProcessStepCreateRequest(
        Integer sequenceOrder,
        @NotBlank String name,
        String description,
        StepType stepType,
        ProcessStepExecutionMode executionMode,
        Boolean automationCandidate,
        String automationRationale,
        UUID linkedSystemId,
        UUID assignedPersonId,
        Integer estimatedMinutes
) {}
