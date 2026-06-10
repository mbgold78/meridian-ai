package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.PhaseStateService;
import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/phase-states")
public class PhaseStateController {

    private final PhaseStateService phaseStateService;

    public PhaseStateController(PhaseStateService phaseStateService) {
        this.phaseStateService = phaseStateService;
    }

    @GetMapping
    public List<PhaseState> listByDomain(@RequestParam UUID domainId) {
        return phaseStateService.listByDomain(domainId);
    }

    @GetMapping("/{phase}")
    public PhaseState getByDomainAndPhase(@RequestParam UUID domainId,
                                          @PathVariable LifecyclePhase phase) {
        return phaseStateService.getOrPlaceholder(domainId, phase);
    }

    @PostMapping("/initialize")
    public List<PhaseState> initialize(@RequestParam UUID domainId) {
        return phaseStateService.initializeForDomain(domainId);
    }
}
