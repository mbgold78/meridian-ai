package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.InitiativeProgressRequest;
import com.meridian.substrate.app.api.v1.request.TransformationInitiativeCreateRequest;
import com.meridian.substrate.app.api.v1.request.TransformationPlanCreateRequest;
import com.meridian.substrate.app.core.service.TransformationService;
import com.meridian.substrate.common.domain.TransformationInitiative;
import com.meridian.substrate.common.domain.TransformationPlan;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * D5 thin-slice transformation write verbs (engine-spec diagnostic.md §10.1;
 * ADR-004 D13 — declared S4 deviation). Operator-grade CRUD only: no
 * wave/state-machine validation exists (spec §15.9,
 * EXTENSION POINT(diagnostic-9)). The fixture {@code GET /transformation/plan}
 * stays behind its flag on {@code TransformationController}, untouched.
 * Entities ARE the wire format (canonical-model convention).
 */
@RestController
@RequestMapping("/api/v1")
public class InitiativeController {

    private final TransformationService transformationService;

    public InitiativeController(TransformationService transformationService) {
        this.transformationService = transformationService;
    }

    /** {@code POST /api/v1/transformation/plans} → 201 plan. */
    @PostMapping("/transformation/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public TransformationPlan createPlan(@Valid @RequestBody TransformationPlanCreateRequest request) {
        return transformationService.createPlan(request);
    }

    /** {@code POST /api/v1/transformation/plans/{id}/initiatives} → 201 initiative. */
    @PostMapping("/transformation/plans/{id}/initiatives")
    @ResponseStatus(HttpStatus.CREATED)
    public TransformationInitiative addInitiative(
            @PathVariable("id") UUID planId,
            @Valid @RequestBody TransformationInitiativeCreateRequest request) {
        return transformationService.addInitiative(planId, request);
    }

    /**
     * {@code PATCH /api/v1/initiatives/{id}/progress} → 200 — the §12.5
     * operator progress patch ({@code @Governed("initiative.update_progress")}
     * on the service verb, ADR-004 D12).
     */
    @PatchMapping("/initiatives/{id}/progress")
    public TransformationInitiative updateProgress(
            @PathVariable("id") UUID initiativeId,
            @Valid @RequestBody InitiativeProgressRequest request) {
        return transformationService.updateProgress(initiativeId, request);
    }
}
