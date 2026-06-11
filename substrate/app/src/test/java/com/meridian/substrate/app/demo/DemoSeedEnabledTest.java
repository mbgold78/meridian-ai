package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EntityOverlapRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.PhaseStateRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessRoleRepository;
import com.meridian.substrate.common.repo.ProcessStepRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import com.meridian.substrate.common.repo.RoleOverlapRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import com.meridian.substrate.engine.decision.log.HashChainVerification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C10 acceptance, enabled half (port-map §7): dev boot with
 * {@code substrate.demo.seed=true} populates an {@code isDemo} domain.
 *
 * <p>Seeding runs on {@link org.springframework.boot.context.event.ApplicationReadyEvent}
 * during context startup, so by the time test methods execute the DemoPack has
 * been applied. Expected row counts are read from the demopack JSON fixtures
 * themselves (all cross-references in the shipped fixtures resolve, so the
 * name-resolution skip paths contribute zero attrition).
 *
 * <p>Uses a private H2 database (the dev-profile default {@code mem:substrate}
 * is shared JVM-wide across cached test contexts; exact-count assertions need
 * an unshared DB).
 */
@SpringBootTest(properties = {
        "substrate.demo.seed=true",
        "spring.datasource.url=jdbc:h2:mem:demoseed_enabled;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class DemoSeedEnabledTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired IntegrationRepository integrationRepository;
    @Autowired OrganizationMemberRepository organizationMemberRepository;
    @Autowired RoleOverlapRepository roleOverlapRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessSystemLinkRepository processSystemLinkRepository;
    @Autowired ProcessRoleRepository processRoleRepository;
    @Autowired ProcessStepRepository processStepRepository;
    @Autowired CanonicalEntityRepository canonicalEntityRepository;
    @Autowired EntityOverlapRepository entityOverlapRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired DesignDecisionRepository designDecisionRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired DocumentRegistryRepository documentRegistryRepository;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PhaseStateRepository phaseStateRepository;
    @Autowired ReaderRegistrationRepository readerRegistrationRepository;
    @Autowired BrandingConfigRepository brandingConfigRepository;
    @Autowired DecisionLogService decisionLogService;

    private int fixtureCount(String path) throws Exception {
        return SeedFiles.load(objectMapper, path).size();
    }

    @Test
    void bulkSeedCreatesExactlyOnePrimaryDemoDomainPlusBeaconSatellites() {
        List<Domain> domains = domainRepository.findAll();
        // Phase 6a: AgentDemoSeeder adds one Beacon SATELLITE domain per
        // fixture subsidiary (22 — a Beacon subsidiary IS a Domain,
        // engine-spec agents.md §15), all isDemo, all flagged
        // metadata.beaconSubsidiary. Exactly ONE primary demo domain remains.
        List<Domain> primaries = domains.stream()
                .filter(d -> d.getMetadata() == null
                        || !d.getMetadata().path("beaconSubsidiary").asBoolean(false))
                .toList();
        assertThat(primaries).hasSize(1);
        assertThat(primaries.get(0).isDemo())
                .as("DemoPack domain must be marked isDemo=true (port-map §5 S1)")
                .isTrue();
        assertThat(primaries.get(0).getName()).isEqualTo("Operational Risk");
        assertThat(primaries.get(0).getCreatedBy()).isEqualTo("seed");

        List<Domain> satellites = domains.stream()
                .filter(d -> !primaries.contains(d))
                .toList();
        assertThat(satellites).hasSize(22);
        assertThat(satellites)
                .as("every Beacon satellite is a demo domain (trap #9: demo data never ships)")
                .allSatisfy(d -> {
                    assertThat(d.isDemo()).isTrue();
                    assertThat(d.getCreatedBy()).isEqualTo("seed");
                    assertThat(d.getMetadata().get("beaconSlug").asText()).isNotBlank();
                });
    }

    @Test
    void bulkSeedPopulatesEveryBoundedContextAtFixtureCounts() throws Exception {
        assertThat(systemRepository.count()).isEqualTo(fixtureCount("demopack/systems.json"));
        assertThat(integrationRepository.count()).isEqualTo(fixtureCount("demopack/integrations.json"));
        assertThat(organizationMemberRepository.count())
                .isEqualTo(fixtureCount("demopack/organization-members.json"));
        assertThat(roleOverlapRepository.count()).isEqualTo(fixtureCount("demopack/role-overlaps.json"));
        assertThat(processRepository.count()).isEqualTo(fixtureCount("demopack/processes.json"));
        assertThat(processSystemLinkRepository.count())
                .isEqualTo(fixtureCount("demopack/process-system-links.json"));
        assertThat(processRoleRepository.count()).isEqualTo(fixtureCount("demopack/process-roles.json"));
        assertThat(canonicalEntityRepository.count())
                .isEqualTo(fixtureCount("demopack/canonical-entities.json"));
        assertThat(entityOverlapRepository.count()).isEqualTo(fixtureCount("demopack/entity-overlaps.json"));
        assertThat(findingRepository.count()).isEqualTo(fixtureCount("demopack/findings.json"));
        // Phase 6b: the AgentDemoSeeder drives the REAL verb path, and that
        // path chain-mirrors DEPLOYED + PROMOTED anchors into design_decisions
        // (engine-spec agents.md §11/§15; ADR-003 D14/D18) — one AGENT_DEPLOYED
        // per fixture agent + one AGENT_PROMOTED per non-shadow fixture agent,
        // on top of the fixture design decisions.
        assertThat(designDecisionRepository.count())
                .isEqualTo(fixtureCount("demopack/design-decisions.json")
                        + beaconAgentCount(a -> true)
                        + beaconAgentCount(status -> !"shadow".equals(status)));
    }

    /** Beacon fixture agents whose seed stream matches a status predicate (§15 vectors). */
    private long beaconAgentCount(java.util.function.Predicate<String> statusFilter)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode pack = objectMapper.readTree(
                new org.springframework.core.io.ClassPathResource(
                        "demopack/beacon-agents.json").getInputStream());
        long count = 0;
        for (com.fasterxml.jackson.databind.JsonNode sub : pack.get("subsidiaries")) {
            for (com.fasterxml.jackson.databind.JsonNode agent : sub.get("agents")) {
                if (statusFilter.test(agent.get("status").asText())) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void seededFindingsAreFixtureProvenanceNotAi() {
        // §5 S2: every seeded Finding is hand-written fixture data, aiGenerated=false.
        assertThat(findingRepository.findAll())
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.getAiGenerated()).isFalse());
    }

    @Test
    void designDecisionChainIsValidFromGenesis() {
        UUID domainId = domainRepository.findAll().get(0).getId();
        HashChainVerification verification = decisionLogService.verifyChain(domainId);
        assertThat(verification.valid())
                .as("seeded decision chain must verify (GENESIS-rooted)")
                .isTrue();
        assertThat(decisionLogService.getDecisionLog(domainId))
                .first()
                .satisfies(d -> assertThat(d.getPreviousHash()).isEqualTo("GENESIS"));
    }

    @Test
    void phase1SampleSeedersPopulateTheDemoDomain() {
        UUID domainId = domainRepository.findAll().get(0).getId();

        // Phase states: one row per LifecyclePhase (7).
        assertThat(phaseStateRepository.findByDomainId(domainId)).hasSize(7);

        // Capability sample tree: root + child.
        assertThat(capabilityRepository.findByDomainId(domainId))
                .extracting(c -> c.getName())
                .containsExactlyInAnyOrder("customer-onboarding", "kyc-verification");

        // Reader registration sample.
        assertThat(readerRegistrationRepository.findByDomainId(domainId))
                .extracting(r -> r.getName())
                .containsExactly("Ingestion ETL (seed)");

        // Branding: exactly one ACTIVE config for the demo domain.
        assertThat(brandingConfigRepository.findFirstByDomainIdAndStatus(
                domainId, BrandingConfigStatus.ACTIVE)).isPresent();

        // Sample process step ("Intake review") seeded onto the first process.
        assertThat(processStepRepository.findAll())
                .extracting(s -> s.getName())
                .containsExactly("Intake review");
    }

    @Test
    void evidenceSeederWritesSyntheticProvenance() throws Exception {
        int systems = fixtureCount("demopack/systems.json");
        int processes = fixtureCount("demopack/processes.json");

        assertThat(documentRegistryRepository.count()).isEqualTo(2);
        assertThat(evidenceRepository.count()).isEqualTo(systems + processes);
        assertThat(evidenceRepository.findAll())
                .allSatisfy(ev -> assertThat(ev.getExtractor()).isEqualTo("seed"));
    }
}
