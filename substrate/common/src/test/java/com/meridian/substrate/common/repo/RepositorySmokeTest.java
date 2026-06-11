package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
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

import java.time.Instant;
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
    @Autowired private CascadeRepository cascadeRepository;
    @Autowired private CascadeLevelRepository cascadeLevelRepository;
    @Autowired private CascadeLevelActionRepository cascadeLevelActionRepository;
    @Autowired private CascadeDraftRepository cascadeDraftRepository;
    @Autowired private CascadeNotificationRepository cascadeNotificationRepository;

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

        // Cascade engine repositories (engine-spec cascade.md §4)
        assertThat(cascadeRepository.findByDomainId(id)).isEmpty();
        assertThat(cascadeRepository.findByDomainIdAndCurrentState(id, CascadeState.IN_FLIGHT)).isEmpty();
        assertThat(cascadeRepository.findByCascadeGroupIdAndCurrentState(id, CascadeState.IN_FLIGHT)).isEmpty();
        assertThat(cascadeRepository.findByIdForUpdate(id)).isEmpty();
        assertThat(cascadeLevelRepository.findByCascadeIdOrderByDepthAsc(id)).isEmpty();
        assertThat(cascadeLevelRepository.findByCascadeIdAndDepth(id, 1)).isEmpty();
        assertThat(cascadeLevelActionRepository.findById(id)).isEmpty();
        assertThat(cascadeLevelActionRepository.findByCascadeIdOrderByChainSeqAsc(id)).isEmpty();
        assertThat(cascadeLevelActionRepository.findByCascadeIdAndIdempotencyKey(id, "no-such-key")).isEmpty();
        assertThat(cascadeLevelActionRepository.countByCascadeIdAndActedAtAfter(id, Instant.EPOCH)).isZero();
        assertThat(cascadeDraftRepository.findByDomainIdAndStatus(id, CascadeDraftStatus.OPEN)).isEmpty();
        assertThat(cascadeDraftRepository.findByIdForUpdate(id)).isEmpty();
        assertThat(cascadeNotificationRepository.findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(id)).isEmpty();
        assertThat(cascadeNotificationRepository.findByCascadeIdOrderByCreatedAtAsc(id)).isEmpty();
        assertThat(cascadeNotificationRepository.findByCascadeIdAndNotificationType(id, CascadeNotificationType.CASCADE_ISSUED)).isEmpty();
        assertThat(cascadeNotificationRepository.existsByDedupeKey("no-such-dedupe-key")).isFalse();
    }

    /**
     * Cascade aggregate + level + append-only action round-trip on the H2
     * slice: derived queries return persisted rows; the insert-only action
     * repository (extends {@code Repository}, ADR D9) saves and reads back
     * without exposing any update/delete surface.
     */
    @Test
    void cascadeRepositoriesRoundTrip() {
        UUID domainId = UUID.randomUUID();

        Cascade cascade = new Cascade();
        cascade.setDomainId(domainId);
        cascade.setOriginOwnerLabel("Operating Partner");
        cascade.setOriginatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        cascade.setOriginalCritique("Vendor cost growth is outpacing volume.");
        cascade.setAcceptanceCriteria("A plan, or a Decline with documented reasoning.");
        cascade.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        cascade.setProducedBy(ProducedBy.HUMAN);
        cascade.setCurrentState(CascadeState.IN_FLIGHT);
        cascade.setChainSeq(1);
        cascade.setFrontierDepth(1);
        cascade.setFrontierState(CascadeLevelState.LOOKING);
        cascade.setAttentionSignal(CascadeAttentionSignal.ESCALATING);
        cascade = cascadeRepository.save(cascade);

        CascadeLevel level0 = new CascadeLevel();
        level0.setCascadeId(cascade.getId());
        level0.setDepth(0);
        level0.setOwnerLabel("Operating Partner");
        level0.setCurrentState(CascadeLevelState.CASCADED);
        level0.setRefinedCritique("Vendor cost growth is outpacing volume.");
        level0.setActivatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        level0.setActedAt(Instant.parse("2026-04-08T00:00:00Z"));
        cascadeLevelRepository.save(level0);

        CascadeLevel level1 = new CascadeLevel();
        level1.setCascadeId(cascade.getId());
        level1.setDepth(1);
        level1.setOwnerLabel("Curasync COO");
        level1.setCurrentState(CascadeLevelState.LOOKING);
        level1.setActivatedAt(Instant.parse("2026-04-08T00:00:00Z"));
        cascadeLevelRepository.save(level1);

        CascadeLevelAction issue = new CascadeLevelAction();
        issue.setCascadeId(cascade.getId());
        issue.setLevelId(level0.getId());
        issue.setDepth(0);
        issue.setRound(1);
        issue.setAction(CascadeActionType.ISSUE);
        issue.setActorLabel("Operating Partner");
        issue.setRefinedCritique("Vendor cost growth is outpacing volume.");
        issue.setNextOwnerLabel("Curasync COO");
        issue.setActedAt(Instant.parse("2026-04-08T00:00:00Z"));
        issue.setIdempotencyKey("smoke-issue-1");
        issue.setChainSeq(1);
        cascadeLevelActionRepository.save(issue);

        assertThat(cascadeRepository.findByDomainId(domainId)).hasSize(1);
        assertThat(cascadeRepository.findByDomainIdAndCurrentState(domainId, CascadeState.IN_FLIGHT))
                .extracting(Cascade::getAttentionSignal)
                .containsExactly(CascadeAttentionSignal.ESCALATING);
        assertThat(cascadeRepository.findByIdForUpdate(cascade.getId())).isPresent();

        assertThat(cascadeLevelRepository.findByCascadeIdOrderByDepthAsc(cascade.getId()))
                .extracting(CascadeLevel::getDepth)
                .containsExactly(0, 1);
        assertThat(cascadeLevelRepository.findByCascadeIdAndDepth(cascade.getId(), 1))
                .isPresent()
                .get()
                .satisfies(l -> assertThat(l.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING));

        assertThat(cascadeLevelActionRepository.findByCascadeIdOrderByChainSeqAsc(cascade.getId()))
                .extracting(CascadeLevelAction::getAction)
                .containsExactly(CascadeActionType.ISSUE);
        assertThat(cascadeLevelActionRepository.findByCascadeIdAndIdempotencyKey(cascade.getId(), "smoke-issue-1"))
                .isPresent();
        assertThat(cascadeLevelActionRepository.countByCascadeIdAndActedAtAfter(
                cascade.getId(), Instant.parse("2026-04-01T00:00:00Z"))).isEqualTo(1);
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
