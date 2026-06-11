package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.PortfolioReadService;
import com.meridian.substrate.app.core.service.read.PortfolioView;
import com.meridian.substrate.app.core.service.read.SubsidiaryDetailView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * D5 portfolio read surface (engine-spec diagnostic.md §10.1/§12.7; ADR-004
 * D13) — severs fixture stat seam S6. The C9 dashboards swap at the
 * {@code BEACON_PORTFOLIO} factory seam with thin adapters over these two
 * bodies. Read-only by design — the portfolio is a composition, not an
 * aggregate root; the write verbs live on their owning surfaces
 * ({@code InitiativeController}, contract/cascade/agent controllers).
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PortfolioReadService portfolioReadService;

    public PortfolioController(PortfolioReadService portfolioReadService) {
        this.portfolioReadService = portfolioReadService;
    }

    /** {@code GET /api/v1/portfolio} — Operating-Partner composition (§12.7). */
    @GetMapping
    public PortfolioView portfolio() {
        return portfolioReadService.portfolio();
    }

    /** {@code GET /api/v1/portfolio/subsidiaries/{domainId}} — COO composition (§12.7). */
    @GetMapping("/subsidiaries/{domainId}")
    public SubsidiaryDetailView subsidiary(@PathVariable UUID domainId) {
        return portfolioReadService.subsidiary(domainId);
    }
}
