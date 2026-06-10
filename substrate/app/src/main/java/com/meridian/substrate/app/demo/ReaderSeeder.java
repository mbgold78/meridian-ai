package com.meridian.substrate.app.demo;

import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.domain.enums.ReaderStatus;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seeds reader registrations (external data sources like structured CSVs,
 * HTTP probes, file watches). Phase 1 ships a single "Ingestion ETL" example
 * so Map-phase UI has something to render; Phase 2's HTTP probe work will
 * add the real production reader catalog.
 *
 * <p>Idempotent — uses name-based seed-if-missing.
 */
@Component
public class ReaderSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(ReaderSeeder.class);

    private final ReaderRegistrationRepository readerRegistrationRepository;

    public ReaderSeeder(ReaderRegistrationRepository readerRegistrationRepository) {
        this.readerRegistrationRepository = readerRegistrationRepository;
    }

    @Override
    public void seedBulk(SeedContext ctx) {
        seedSamplesForDomain(ctx, ctx.getDomainId());
    }

    @Override
    public void seedSamplesForDomain(SeedContext ctx, UUID domainId) {
        ensureReader(domainId, "Ingestion ETL (seed)", ReaderKind.STRUCTURED);
        log.info("[seed] ReaderSeeder: readers seeded/present [domainId={}]", domainId);
    }

    private void ensureReader(UUID domainId, String name, ReaderKind kind) {
        for (ReaderRegistration existing : readerRegistrationRepository.findByDomainId(domainId)) {
            if (name.equals(existing.getName())) return;
        }
        ReaderRegistration reader = new ReaderRegistration();
        reader.setDomainId(domainId);
        reader.setKind(kind);
        reader.setName(name);
        reader.setStatus(ReaderStatus.ACTIVE);
        reader.setRegisteredAt(Instant.now());
        reader.setCreatedBy("seed");
        readerRegistrationRepository.save(reader);
        log.info("[seed] ReaderSeeder: reader_registration [domainId={}, name='{}']", domainId, name);
    }

    @Override
    public List<Class<? extends Seeder>> dependsOn() {
        return List.of(DomainSeeder.class);
    }
}
