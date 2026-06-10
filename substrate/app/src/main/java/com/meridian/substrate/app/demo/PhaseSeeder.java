package com.meridian.substrate.app.demo;

import com.meridian.substrate.app.core.service.PhaseStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seeds initial phase_states for a demo domain. Delegates to
 * {@link PhaseStateService#initializeForDomain(UUID)}, which is itself
 * idempotent — no-ops if phase states already exist for the domain.
 */
@Component
public class PhaseSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(PhaseSeeder.class);

    private final PhaseStateService phaseStateService;

    public PhaseSeeder(PhaseStateService phaseStateService) {
        this.phaseStateService = phaseStateService;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedSamplesForDomain(ctx, ctx.getDomainId());
    }

    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        phaseStateService.initializeForDomain(domainId);
        log.info("[seed] PhaseSeeder: phase_states initialized [domainId={}]", domainId);
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }
}
