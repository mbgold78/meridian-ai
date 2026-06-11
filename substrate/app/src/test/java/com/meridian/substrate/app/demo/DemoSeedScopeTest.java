package com.meridian.substrate.app.demo;

import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.PhaseStateRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessStepRepository;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoPack scope rules (port-map §5 S1, chunk C10):
 * <ol>
 *   <li>re-running the orchestrator against an already-seeded DB is idempotent
 *       (per-entity-absent sample seeding, no duplicates), and</li>
 *   <li>the DemoPack only ever seeds {@code isDemo} domains — non-demo
 *       (production-shaped) domains are never touched, which is the substrate
 *       hardening over homepoc's iterate-every-domain behavior.</li>
 * </ol>
 *
 * <p>The context boots with {@code substrate.demo.seed=true} on a private H2
 * database, so the bulk seed has already run once before the test methods.
 * Methods are ordered: idempotency is verified before the non-demo domain is
 * introduced.
 */
@SpringBootTest(properties = {
        "substrate.demo.seed=true",
        "spring.datasource.url=jdbc:h2:mem:demoseed_scope;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeedScopeTest {

    @Autowired SeedOrchestrator orchestrator;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessStepRepository processStepRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired DesignDecisionRepository designDecisionRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PhaseStateRepository phaseStateRepository;
    @Autowired ReaderRegistrationRepository readerRegistrationRepository;
    @Autowired BrandingConfigRepository brandingConfigRepository;

    @Test
    @Order(1)
    void rerunningOrchestratorIsIdempotent() {
        long domains = domainRepository.count();
        long systems = systemRepository.count();
        long processes = processRepository.count();
        long steps = processStepRepository.count();
        long findings = findingRepository.count();
        long decisions = designDecisionRepository.count();
        long evidence = evidenceRepository.count();
        long capabilities = capabilityRepository.count();
        long phaseStates = phaseStateRepository.count();
        long readers = readerRegistrationRepository.count();
        long brandings = brandingConfigRepository.count();

        // 1 primary demo domain + 22 Beacon satellite domains (Phase 6a:
        // AgentDemoSeeder — a Beacon subsidiary IS a Domain, agents.md §15).
        assertThat(domains).as("bulk seed ran at boot").isEqualTo(23);

        // Second run: domains exist, so the orchestrator takes the idempotent
        // per-demo-domain sample path (Beacon satellites are SKIPPED — the
        // sample seeders belong to the primary domain only). Nothing may
        // duplicate — including agent satellite domains/registrations.
        orchestrator.run();

        assertThat(domainRepository.count()).isEqualTo(domains);
        assertThat(systemRepository.count()).isEqualTo(systems);
        assertThat(processRepository.count()).isEqualTo(processes);
        assertThat(processStepRepository.count()).isEqualTo(steps);
        assertThat(findingRepository.count()).isEqualTo(findings);
        assertThat(designDecisionRepository.count()).isEqualTo(decisions);
        assertThat(evidenceRepository.count()).isEqualTo(evidence);
        assertThat(capabilityRepository.count()).isEqualTo(capabilities);
        assertThat(phaseStateRepository.count()).isEqualTo(phaseStates);
        assertThat(readerRegistrationRepository.count()).isEqualTo(readers);
        assertThat(brandingConfigRepository.count()).isEqualTo(brandings);
    }

    @Test
    @Order(2)
    void nonDemoDomainsAreNeverSeeded() {
        Domain production = new Domain();
        production.setName("Production Domain (ingested)");
        production.setDescription("Simulates a domain populated through ingestion + engines.");
        production.setDemo(false);
        production.setCreatedBy("test");
        UUID productionId = domainRepository.save(production).getId();

        long capabilitiesBefore = capabilityRepository.count();
        long readersBefore = readerRegistrationRepository.count();
        long brandingsBefore = brandingConfigRepository.count();
        long phaseStatesBefore = phaseStateRepository.count();
        long stepsBefore = processStepRepository.count();

        orchestrator.run();

        // The non-demo domain received nothing from any seeder.
        assertThat(systemRepository.findByDomainId(productionId)).isEmpty();
        assertThat(processRepository.findByDomainId(productionId)).isEmpty();
        assertThat(capabilityRepository.findByDomainId(productionId)).isEmpty();
        assertThat(readerRegistrationRepository.findByDomainId(productionId)).isEmpty();
        assertThat(brandingConfigRepository.findByDomainId(productionId)).isEmpty();
        assertThat(phaseStateRepository.findByDomainId(productionId)).isEmpty();

        // And globally nothing duplicated for the demo domain either.
        assertThat(capabilityRepository.count()).isEqualTo(capabilitiesBefore);
        assertThat(readerRegistrationRepository.count()).isEqualTo(readersBefore);
        assertThat(brandingConfigRepository.count()).isEqualTo(brandingsBefore);
        assertThat(phaseStateRepository.count()).isEqualTo(phaseStatesBefore);
        assertThat(processStepRepository.count()).isEqualTo(stepsBefore);
    }
}
