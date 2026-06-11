package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.ai.assertion.AssertionService;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.exception.RationaleRequiredException;
import com.meridian.substrate.engine.diagnostic.read.CurationDecision;
import com.meridian.substrate.engine.diagnostic.read.DraftCommitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic test plan #8, service half (engine-spec diagnostic.md §5.3;
 * ADR-004 D9; the REST half is the app module's
 * {@code GenerativeCommitApiTest}):
 *
 * <ul>
 *   <li>producedBy/predicate guards — only {@code AI_GENERATIVE
 *       'proposes_creation'} assertions materialize;</li>
 *   <li>grounding RE-VERIFIED at commit time → 409
 *       {@code DRAFT_GROUNDING_LOST}, nothing materialized;</li>
 *   <li>{@code parentId→parentCapabilityId} rename (+ unresolvable parent →
 *       parentless, {@code metadata.unresolvedParent=true});</li>
 *   <li>human overrides apply AFTER asserted fields, delta recorded on the
 *       ACCEPT event;</li>
 *   <li>reject creates NO entity; double-accept and double-reject are
 *       idempotent; accept-vs-reject contradictions are 409;</li>
 *   <li>NO bulk verb exists — compile-time absence pinned by reflection
 *       (the API-surface half lives in the app test).</li>
 * </ul>
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Transactional
class DraftCommitServiceTest {

    @Autowired DraftCommitService draftCommitService;
    @Autowired AssertionService assertionService;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired AiCurationEventRepository eventRepository;
    @Autowired ObjectMapper mapper;

    private UUID domainId;
    private Evidence evidence;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("draft-commit " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();
        evidence = evidence("glacier-workflow-notes.md");
    }

    // ----------------------------------------------------------- happy path

    @Test
    void commitDraft_materializesDraftCapability_withRenameAndAcceptEvent() {
        Capability parent = capability("Operations", null, 0);
        ObjectNode fields = mapper.createObjectNode()
                .put("name", "claims-intake")
                .put("displayName", "Claims Intake")
                .put("description", "Proposed from workflow notes.")
                .put("parentId", parent.getId().toString()); // doc-04 spec name
        Assertion draft = draft(fields, List.of(evidence.getId()));

        DraftCommitResult result = draftCommitService.commitDraft(
                draft.getId(), null, "k-1", "Avery Operator", "p-9");

        Capability committed = result.capability();
        assertThat(result.replayed()).isFalse();
        assertThat(committed.getStatus()).isEqualTo(CapabilityStatus.DRAFT);
        assertThat(committed.isAiGenerated()).isTrue();
        assertThat(committed.getName()).isEqualTo("claims-intake");
        assertThat(committed.getDisplayName()).isEqualTo("Claims Intake");
        assertThat(committed.getDescription()).isEqualTo("Proposed from workflow notes.");
        // parentId→parentCapabilityId rename (GenerativeModeService javadoc contract)
        assertThat(committed.getParentCapabilityId()).isEqualTo(parent.getId());
        assertThat(committed.getLevel()).isEqualTo(1);
        assertThat(committed.getMetadata().get("assertionId").asText())
                .isEqualTo(draft.getId().toString());
        assertThat(committed.getMetadata().has("unresolvedParent")).isFalse();

        List<AiCurationEvent> events = eventsOf(draft.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo(CurationAction.ACCEPT);
        assertThat(events.get(0).getCreatedEntityId()).isEqualTo(committed.getId());
        assertThat(events.get(0).getOverriddenFields()).isNull(); // no human edits
        assertThat(events.get(0).getActorLabel()).isEqualTo("Avery Operator");
    }

    @Test
    void overrides_applyAfterAssertedFields_andTheDeltaIsProvenance() {
        ObjectNode fields = mapper.createObjectNode()
                .put("name", "ai-proposed-name")
                .put("description", "AI description.");
        Assertion draft = draft(fields, List.of(evidence.getId()));
        ObjectNode overrides = mapper.createObjectNode()
                .put("name", "human-corrected-name")
                .put("description", "AI description.")  // no-op — NOT a human edit
                .put("reasoning", "dropped — not allowlisted");

        DraftCommitResult result = draftCommitService.commitDraft(
                draft.getId(), overrides, "k-1", "Avery", null);

        assertThat(result.capability().getName()).isEqualTo("human-corrected-name");
        assertThat(result.capability().getDescription()).isEqualTo("AI description.");
        // the recorded delta carries ONLY the real human edits
        assertThat(result.overriddenFields()).isNotNull();
        assertThat(result.overriddenFields().get("name").asText()).isEqualTo("human-corrected-name");
        assertThat(result.overriddenFields().has("description")).isFalse();
        assertThat(result.overriddenFields().has("reasoning")).isFalse();
        assertThat(eventsOf(draft.getId()).get(0).getOverriddenFields())
                .isEqualTo(result.overriddenFields());
    }

    @Test
    void unresolvableParent_commitsParentless_andIsFlagged_neverSilent() {
        ObjectNode fields = mapper.createObjectNode()
                .put("name", "orphan-capability")
                .put("parentId", UUID.randomUUID().toString()); // resolves to nothing
        Assertion draft = draft(fields, List.of(evidence.getId()));

        Capability committed = draftCommitService.commitDraft(
                draft.getId(), null, "k-1", "Avery", null).capability();

        assertThat(committed.getParentCapabilityId()).isNull();
        assertThat(committed.getLevel()).isZero();
        assertThat(committed.getMetadata().get("unresolvedParent").asBoolean()).isTrue();
    }

    // --------------------------------------------------------------- guards

    @Test
    void producedByGuard_nonGenerativeAssertion_isRefused() {
        Assertion validative = assertionService.record(domainId, "finding:x", "proposes_creation",
                "{\"name\":\"x\"}", null, List.of(evidence.getId()),
                ProducedBy.AI_VALIDATIVE, null);

        assertThatThrownBy(() -> draftCommitService.commitDraft(
                validative.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI_GENERATIVE");
        assertThat(capabilityRepository.findByDomainId(domainId)).isEmpty();
    }

    @Test
    void predicateGuard_nonCreationProposal_isRefused() {
        Assertion wrongPredicate = assertionService.record(domainId, "draft:capability",
                "proposes_update", "{\"name\":\"x\"}", null, List.of(evidence.getId()),
                ProducedBy.AI_GENERATIVE, null);

        assertThatThrownBy(() -> draftCommitService.commitDraft(
                wrongPredicate.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposes_creation");
    }

    @Test
    void groundingReverifiedAtCommit_staleEvidence_isDraftGroundingLost409() {
        Assertion draft = draft(mapper.createObjectNode().put("name", "stale-draft"),
                List.of(evidence.getId(), UUID.randomUUID())); // one real, one gone

        assertThatThrownBy(() -> draftCommitService.commitDraft(
                draft.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.DRAFT_GROUNDING_LOST));
        // never silently materialized — no entity, no event
        assertThat(capabilityRepository.findByDomainId(domainId)).isEmpty();
        assertThat(eventsOf(draft.getId())).isEmpty();
    }

    @Test
    void draftCitingNoEvidence_isEquallyGroundingLost() {
        Assertion draft = draft(mapper.createObjectNode().put("name", "ungrounded"), List.of());

        assertThatThrownBy(() -> draftCommitService.commitDraft(
                draft.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.DRAFT_GROUNDING_LOST));
    }

    @Test
    void draftWithoutNameOrDisplayName_cannotMaterialize() {
        Assertion draft = draft(mapper.createObjectNode().put("description", "nameless"),
                List.of(evidence.getId()));

        assertThatThrownBy(() -> draftCommitService.commitDraft(
                draft.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    // ------------------------------------------------ idempotency + reject

    @Test
    void doubleAccept_isIdempotent_oneCapabilityOneEvent() {
        Assertion draft = draft(mapper.createObjectNode().put("name", "idem"),
                List.of(evidence.getId()));
        DraftCommitResult first = draftCommitService.commitDraft(
                draft.getId(), null, "k-1", "Avery", null);

        DraftCommitResult replay = draftCommitService.commitDraft(
                draft.getId(), null, "k-2", "Someone Else", null); // even with a FRESH key

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.capability().getId()).isEqualTo(first.capability().getId());
        assertThat(capabilityRepository.findByDomainId(domainId)).hasSize(1);
        assertThat(eventsOf(draft.getId())).hasSize(1);
    }

    @Test
    void reject_createsNoEntityRow_eventOnly() {
        Assertion draft = draft(mapper.createObjectNode().put("name", "rejected"),
                List.of(evidence.getId()));

        CurationDecision decision = draftCommitService.rejectDraft(
                draft.getId(), "proposal duplicates an existing capability", "k-1", "Avery", null);

        assertThat(decision.curationStatus()).isEqualTo(CurationStatus.REJECTED);
        assertThat(capabilityRepository.findByDomainId(domainId))
                .as("rejection needs no entity row — the Assertion IS the draft store")
                .isEmpty();
        List<AiCurationEvent> events = eventsOf(draft.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo(CurationAction.REJECT);
        assertThat(events.get(0).getCreatedEntityId()).isNull();
    }

    @Test
    void rejectWithoutRationale_isRationaleRequired() {
        Assertion draft = draft(mapper.createObjectNode().put("name", "x"),
                List.of(evidence.getId()));

        assertThatThrownBy(() -> draftCommitService.rejectDraft(
                draft.getId(), null, "k-1", "Avery", null))
                .isInstanceOf(RationaleRequiredException.class);
        assertThat(eventsOf(draft.getId())).isEmpty();
    }

    @Test
    void doubleReject_isIdempotentReplay() {
        Assertion draft = draft(mapper.createObjectNode().put("name", "x"),
                List.of(evidence.getId()));
        CurationDecision first = draftCommitService.rejectDraft(
                draft.getId(), "duplicate", "k-1", "Avery", null);

        CurationDecision replay = draftCommitService.rejectDraft(
                draft.getId(), null, "k-2", "Avery", null);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(eventsOf(draft.getId())).hasSize(1);
    }

    @Test
    void acceptVsRejectContradictions_are409CurationConflicts() {
        Assertion accepted = draft(mapper.createObjectNode().put("name", "a"),
                List.of(evidence.getId()));
        draftCommitService.commitDraft(accepted.getId(), null, "k-1", "Avery", null);
        assertThatThrownBy(() -> draftCommitService.rejectDraft(
                accepted.getId(), "too late", "k-2", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.CURATION_CONFLICT));

        Assertion rejected = draft(mapper.createObjectNode().put("name", "b"),
                List.of(evidence.getId()));
        draftCommitService.rejectDraft(rejected.getId(), "no", "k-3", "Avery", null);
        assertThatThrownBy(() -> draftCommitService.commitDraft(
                rejected.getId(), null, "k-4", "Avery", null))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.CURATION_CONFLICT));
        assertThat(capabilityRepository.findByDomainId(domainId)).hasSize(1); // only the accepted one
    }

    // ----------------------------------------------- no-bulk-verb (compile time)

    /**
     * Test plan #8: "no bulk verb exists (compile-time absence…)". Pins the
     * curation/draft PUBLIC verb surfaces structurally: every public verb
     * takes exactly ONE subject id — no Collection/array parameters, no
     * bulk/batch naming. (The package-private finalization supersession is
     * NOT an operator verb — its package-privacy is itself the "no operator
     * supersede verb" pin, spec §9.) The API-surface half (controller
     * mappings) lives in the app module's {@code GenerativeCommitApiTest}.
     */
    @Test
    void noBulkVerbExists_onTheCurationOrDraftSurfaces() {
        for (Class<?> service : List.of(CurationService.class, DraftCommitService.class)) {
            for (Method method : service.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("no bulk/batch verb on %s", service.getSimpleName())
                        .doesNotContain("bulk")
                        .doesNotContain("batch")
                        .doesNotContain("acceptall")
                        .doesNotContain("rejectall");
                for (Class<?> param : method.getParameterTypes()) {
                    assertThat(Collection.class.isAssignableFrom(param) || param.isArray())
                            .as("%s.%s must not take a collection/array of subjects "
                                            + "(per-item acceptance is structural — P6/doc-07)",
                                    service.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }
        // and no public supersede verb exists at all (system-authored at
        // finalization only — spec §9)
        for (Method method : CurationService.class.getMethods()) {
            if (method.getDeclaringClass() == CurationService.class) {
                assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .as("no operator supersede verb (spec §9)")
                        .doesNotContain("supersede");
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private Assertion draft(ObjectNode proposedFields, List<UUID> evidenceIds) {
        return assertionService.record(domainId, "draft:capability",
                "proposes_creation", proposedFields.toString(), 0.9f, evidenceIds,
                ProducedBy.AI_GENERATIVE, null);
    }

    private Capability capability(String name, UUID parentId, int level) {
        Capability capability = new Capability();
        capability.setDomainId(domainId);
        capability.setName(name);
        capability.setDisplayName(name);
        capability.setParentCapabilityId(parentId);
        capability.setLevel(level);
        capability.setStatus(CapabilityStatus.ACTIVE);
        capability.setCreatedBy("test");
        return capabilityRepository.save(capability);
    }

    private Evidence evidence(String sourceFile) {
        Evidence row = new Evidence();
        row.setDomainId(domainId);
        row.setEntityId(UUID.randomUUID());
        row.setEntityType("capability");
        row.setLabel("Committed from " + sourceFile);
        row.setSourceFile(sourceFile);
        row.setExtractor("ingestion");
        row.setConfidence(0.95f);
        row.setDocumentRegistryId(UUID.randomUUID());
        row.setCreatedBy("test");
        return evidenceRepository.save(row);
    }

    private List<AiCurationEvent> eventsOf(UUID assertionId) {
        return eventRepository.findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(
                CurationSubjectKind.DRAFT, assertionId);
    }
}
