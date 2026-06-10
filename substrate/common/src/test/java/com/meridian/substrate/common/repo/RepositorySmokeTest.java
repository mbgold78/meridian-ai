package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap + query-derivation smoke test for the unified repository layer.
 *
 * <p>Every derived-query and JPQL method on all 26 repositories is invoked at
 * least once. With Spring Data's lazy slice bootstrap, query methods are only
 * parsed/compiled against the entity metamodel on first use — so invoking each
 * method is what actually verifies that property paths (e.g.
 * {@code findByDomainIdAndProducedBy}) match the ported A1 entities, which
 * plain compilation cannot catch.
 *
 * <p>Runs on H2 in PostgreSQL compatibility mode (same as the dev profile;
 * required because the entities declare {@code columnDefinition = "TEXT"},
 * which H2 2.x only accepts in PG mode). Per the port map (§4.4), H2-green is
 * NOT the Postgres acceptance gate — the Testcontainers-Postgres suite in CI
 * (chunk C11) remains the authority for dialect behavior.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:repo-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositorySmokeTest {

    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemRepository systemRepository;
    @Autowired private SystemUploadRepository systemUploadRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private RoleOverlapRepository roleOverlapRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessStepRepository processStepRepository;
    @Autowired private ProcessRoleRepository processRoleRepository;
    @Autowired private ProcessSystemLinkRepository processSystemLinkRepository;
    @Autowired private CanonicalEntityRepository canonicalEntityRepository;
    @Autowired private EntityOverlapRepository entityOverlapRepository;
    @Autowired private IntegrationRepository integrationRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private DesignDecisionRepository designDecisionRepository;
    @Autowired private ChatInteractionRepository chatInteractionRepository;
    @Autowired private SynthesisRunRepository synthesisRunRepository;
    @Autowired private DocumentRegistryRepository documentRegistryRepository;
    @Autowired private EvidenceRepository evidenceRepository;
    @Autowired private AssertionRepository assertionRepository;
    @Autowired private CapabilityRepository capabilityRepository;
    @Autowired private CapabilityNameHistoryRepository capabilityNameHistoryRepository;
    @Autowired private CapabilityProcessLinkRepository capabilityProcessLinkRepository;
    @Autowired private CapabilitySystemLinkRepository capabilitySystemLinkRepository;
    @Autowired private PhaseStateRepository phaseStateRepository;
    @Autowired private ReaderRegistrationRepository readerRegistrationRepository;
    @Autowired private BrandingConfigRepository brandingConfigRepository;

    @Test
    void allDerivedAndJpqlQueriesParseAndExecute() {
        UUID id = UUID.randomUUID();

        assertThat(domainRepository.count()).isZero();

        assertThat(systemRepository.findByDomainId(id)).isEmpty();
        assertThat(systemUploadRepository.findBySystemId(id)).isEmpty();
        assertThat(organizationMemberRepository.findByDomainId(id)).isEmpty();
        assertThat(roleOverlapRepository.findByDomainId(id)).isEmpty();

        assertThat(processRepository.findByDomainId(id)).isEmpty();
        assertThat(processRepository.findByClassification(ManualProcessClassification.RECONCILIATION)).isEmpty();
        assertThat(processStepRepository.findByProcessIdOrderBySequenceOrderAsc(id)).isEmpty();
        assertThat(processRoleRepository.findByProcessId(id)).isEmpty();
        assertThat(processRoleRepository.findByMemberId(id)).isEmpty();
        assertThat(processSystemLinkRepository.findByProcessId(id)).isEmpty();
        assertThat(processSystemLinkRepository.findBySystemId(id)).isEmpty();

        assertThat(canonicalEntityRepository.findByDomainId(id)).isEmpty();
        assertThat(canonicalEntityRepository.findBySystemId(id)).isEmpty();
        assertThat(entityOverlapRepository.findByDomainId(id)).isEmpty();
        assertThat(integrationRepository.findByDomainId(id)).isEmpty();
        assertThat(integrationRepository.findBySourceSystemIdOrTargetSystemId(id, id)).isEmpty();

        assertThat(findingRepository.findByDomainId(id)).isEmpty();
        assertThat(findingRepository.findByFindingType(FindingType.DUPLICATION)).isEmpty();
        assertThat(findingRepository.findByDomainIdAndFindingType(id, FindingType.COVERAGE_GAP)).isEmpty();

        assertThat(designDecisionRepository.findByDomainId(id)).isEmpty();
        assertThat(designDecisionRepository.findByDomainIdAndStatus(id, DecisionStatus.PROPOSED)).isEmpty();

        assertThat(chatInteractionRepository.findByDomainIdOrderByCreatedAtDesc(id)).isEmpty();
        assertThat(synthesisRunRepository.findByDomainId(id)).isEmpty();
        assertThat(documentRegistryRepository.findBySourceFileSha256("no-such-sha256")).isEmpty();

        assertThat(evidenceRepository.findByEntityId(id)).isEmpty();
        assertThat(evidenceRepository.findByDomainId(id)).isEmpty();
        assertThat(evidenceRepository.findByEntityIdAndEntityType(id, "Finding")).isEmpty();
        assertThat(evidenceRepository.findByDocumentRegistryId(id)).isEmpty();

        assertThat(assertionRepository.findByDomainId(id)).isEmpty();
        assertThat(assertionRepository.findByDomainIdAndProducedBy(id, ProducedBy.AI_GENERATIVE)).isEmpty();
        assertThat(assertionRepository.findBySubject("no-such-subject")).isEmpty();

        assertThat(capabilityRepository.findByDomainId(id)).isEmpty();
        assertThat(capabilityRepository.findByDomainIdAndParentCapabilityIdIsNull(id)).isEmpty();
        assertThat(capabilityRepository.findSiblingsUnderParent(id, id)).isEmpty();
        assertThat(capabilityRepository.findRootSiblings(id)).isEmpty();
        assertThat(capabilityRepository.findSiblingsUnderParentForUpdate(id, id)).isEmpty();
        assertThat(capabilityRepository.findRootSiblingsForUpdate(id)).isEmpty();

        assertThat(capabilityNameHistoryRepository.findByCapabilityIdOrderByCreatedAtAsc(id)).isEmpty();
        assertThat(capabilityProcessLinkRepository.findByCapabilityId(id)).isEmpty();
        assertThat(capabilityProcessLinkRepository.findByProcessId(id)).isEmpty();
        assertThat(capabilitySystemLinkRepository.findByCapabilityId(id)).isEmpty();
        assertThat(capabilitySystemLinkRepository.findBySystemId(id)).isEmpty();

        assertThat(phaseStateRepository.findByDomainId(id)).isEmpty();
        assertThat(phaseStateRepository.findByDomainIdAndPhase(id, LifecyclePhase.DESIGN)).isEmpty();

        assertThat(readerRegistrationRepository.findByDomainId(id)).isEmpty();
        assertThat(readerRegistrationRepository.findByDomainIdAndKind(id, ReaderKind.STRUCTURED)).isEmpty();

        assertThat(brandingConfigRepository.findByDomainId(id)).isEmpty();
        assertThat(brandingConfigRepository.findFirstByDomainIdAndStatus(id, BrandingConfigStatus.ACTIVE)).isEmpty();
        assertThat(brandingConfigRepository.findFirstByDomainIdAndStatusForUpdate(id, BrandingConfigStatus.ACTIVE)).isEmpty();
    }

    @Test
    void capabilitySiblingQueriesReturnSequenceOrderedRows() {
        Domain domain = new Domain();
        domain.setName("smoke-domain");
        domain = domainRepository.save(domain);
        UUID domainId = domain.getId();

        Capability root = newCapability(domainId, null, "root", 0, 0);
        root = capabilityRepository.save(root);
        UUID rootId = root.getId();

        // Saved deliberately out of sequence order.
        capabilityRepository.save(newCapability(domainId, rootId, "child-b", 1, 2));
        capabilityRepository.save(newCapability(domainId, rootId, "child-a", 1, 1));
        capabilityRepository.save(newCapability(domainId, rootId, "child-c", 1, 3));

        List<Capability> siblings = capabilityRepository.findSiblingsUnderParent(domainId, rootId);
        assertThat(siblings).extracting(Capability::getName)
                .containsExactly("child-a", "child-b", "child-c");

        List<Capability> lockedSiblings = capabilityRepository.findSiblingsUnderParentForUpdate(domainId, rootId);
        assertThat(lockedSiblings).extracting(Capability::getName)
                .containsExactly("child-a", "child-b", "child-c");

        assertThat(capabilityRepository.findRootSiblings(domainId))
                .extracting(Capability::getName)
                .containsExactly("root");
        assertThat(capabilityRepository.findRootSiblingsForUpdate(domainId))
                .extracting(Capability::getName)
                .containsExactly("root");

        assertThat(capabilityRepository.findByDomainIdAndParentCapabilityIdIsNull(domainId))
                .extracting(Capability::getName)
                .containsExactly("root");
        assertThat(capabilityRepository.findByDomainId(domainId)).hasSize(4);
    }

    @Test
    void phaseStateAndEvidenceRoundTrip() {
        Domain domain = new Domain();
        domain.setName("smoke-domain-2");
        domain = domainRepository.save(domain);
        UUID domainId = domain.getId();

        PhaseState phaseState = new PhaseState();
        phaseState.setDomainId(domainId);
        phaseState.setPhase(LifecyclePhase.MAP);
        phaseState.setStatus(PhaseStatus.IN_PROGRESS);
        phaseStateRepository.save(phaseState);

        assertThat(phaseStateRepository.findByDomainId(domainId)).hasSize(1);
        assertThat(phaseStateRepository.findByDomainIdAndPhase(domainId, LifecyclePhase.MAP))
                .isPresent()
                .get()
                .satisfies(ps -> assertThat(ps.getStatus()).isEqualTo(PhaseStatus.IN_PROGRESS));
        assertThat(phaseStateRepository.findByDomainIdAndPhase(domainId, LifecyclePhase.EXECUTE)).isEmpty();

        UUID entityId = UUID.randomUUID();
        Evidence evidence = new Evidence();
        evidence.setEntityId(entityId);
        evidence.setEntityType("Finding");
        evidence.setDomainId(domainId);
        evidence.setSourceFile("samples/source.csv");
        evidence.setExtractor("smoke-test");
        evidenceRepository.save(evidence);

        assertThat(evidenceRepository.findByEntityId(entityId)).hasSize(1);
        assertThat(evidenceRepository.findByEntityIdAndEntityType(entityId, "Finding")).hasSize(1);
        assertThat(evidenceRepository.findByEntityIdAndEntityType(entityId, "Capability")).isEmpty();
        assertThat(evidenceRepository.findByDomainId(domainId)).hasSize(1);
    }

    private static Capability newCapability(UUID domainId, UUID parentId, String name, int level, int sequenceOrder) {
        Capability capability = new Capability();
        capability.setDomainId(domainId);
        capability.setParentCapabilityId(parentId);
        capability.setName(name);
        capability.setDisplayName(name);
        capability.setLevel(level);
        capability.setAiGenerated(false);
        capability.setSequenceOrder(sequenceOrder);
        return capability;
    }
}
