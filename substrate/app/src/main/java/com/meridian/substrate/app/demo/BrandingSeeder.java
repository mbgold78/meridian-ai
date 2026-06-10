package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.branding.BrandingDefaults;
import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Ensures every demo Domain has exactly one ACTIVE {@link BrandingConfig}. On a
 * fresh DB this seeds the default config for the bulk-seeded demo domain;
 * on existing DBs (idempotent re-entry) it adds a default config for any
 * demo domain that lacks an ACTIVE row, so dev environments pick up the new
 * branding row without a wipe.
 *
 * <p>Depends on {@link DomainSeeder} — needs the domain UUIDs in the
 * {@link SeedContext} (bulk path) or in the database (idempotent path)
 * before it can attach configs.
 */
@Component
public class BrandingSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(BrandingSeeder.class);

    private final BrandingConfigRepository repository;
    private final ObjectMapper mapper;

    public BrandingSeeder(BrandingConfigRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        UUID domainId = ctx.getDomainId();
        if (domainId == null) {
            log.warn("[seed] BrandingSeeder: no domainId in context — DomainSeeder did not run?");
            return;
        }
        seedDefaultIfAbsent(domainId);
    }

    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        seedDefaultIfAbsent(domainId);
    }

    private void seedDefaultIfAbsent(UUID domainId) {
        if (repository.findFirstByDomainIdAndStatus(domainId, BrandingConfigStatus.ACTIVE).isPresent()) {
            return;
        }
        BrandingConfig defaultConfig = BrandingDefaults.newDefaultFor(domainId, mapper);
        BrandingConfig saved = repository.save(defaultConfig);
        log.info("[seed] BrandingSeeder: default ACTIVE config [id={}, domainId={}]",
                saved.getId(), domainId);
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }
}
