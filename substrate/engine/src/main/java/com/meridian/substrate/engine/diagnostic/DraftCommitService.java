package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.AssertionRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.exception.RationaleRequiredException;
import com.meridian.substrate.engine.diagnostic.read.CurationDecision;
import com.meridian.substrate.engine.diagnostic.read.DraftCommitResult;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DraftEntity → Capability — the generative sibling of the finding commit
 * step (engine-spec diagnostic.md §5.3; ADR-004 D9). The
 * {@code AI_GENERATIVE} Assertion row IS the durable draft store
 * ({@code assertion.object} holds the allowlisted {@code proposedFields});
 * acceptance materializes {@code Capability(status=DRAFT, aiGenerated=true)},
 * rejection appends an audit event and creates NO entity row.
 *
 * <p>Hard guarantees:
 * <ul>
 *   <li>{@code producedBy=AI_GENERATIVE ∧ predicate='proposes_creation'} or
 *       the verb is refused — only mode-service-filtered drafts ever
 *       materialize (P6: one grounding chokepoint).</li>
 *   <li><b>Grounding is RE-VERIFIED at materialization time</b>: every
 *       {@code assertion.evidenceIds} entry must STILL resolve to an
 *       in-domain Evidence row → otherwise 409 {@code DRAFT_GROUNDING_LOST}
 *       (the proposal is stale against current evidence; never silently
 *       materialized).</li>
 *   <li>{@code parentId→parentCapabilityId} rename per the
 *       GenerativeModeService javadoc contract; an unresolvable parent
 *       commits parentless with {@code metadata.unresolvedParent=true} (no
 *       silent drop).</li>
 *   <li>Human {@code overrides} apply AFTER the asserted fields, restricted
 *       to the same proposable-field allowlist; the applied delta is recorded
 *       as {@code overridden_fields} on the ACCEPT event — the human edit is
 *       itself provenance.</li>
 *   <li>Double-accept is idempotent (200, the existing capability);
 *       accept-vs-reject contradictions are 409 {@code CURATION_CONFLICT}.</li>
 *   <li><b>There is NO bulk-accept verb</b> — per-item acceptance is
 *       structural (P6/doc-07; test plan #8 pins compile-time absence).</li>
 * </ul>
 *
 * <p>DRAFT-kind audit events are written through
 * {@link CurationService#appendEvent} — one audit table, one discipline
 * (spec §9).
 *
 * <p>EXTENSION POINT(diagnostic-6): generative targets beyond Capability.
 * Capability is the ONLY materialization target in v1 — the mode service has
 * no other allowlist, and this commit step matches it deliberately; a second
 * entity type shipping through the generative path unblocks the seam. Flag,
 * don't invent.
 */
@Service
public class DraftCommitService {

    private static final Logger log = LoggerFactory.getLogger(DraftCommitService.class);

    static final String PREDICATE_PROPOSES_CREATION = "proposes_creation";

    /**
     * The Capability proposable-field allowlist — deliberately the SAME set
     * {@code GenerativeModeService.ALLOWED_FIELDS_BY_ENTITY_TYPE} enforces at
     * proposal time ({@code parentId} is the doc-04 spec name; the rename to
     * the JPA {@code parentCapabilityId} happens here, per the mode-service
     * javadoc contract). Human overrides are restricted to this same set.
     */
    static final Set<String> PROPOSABLE_FIELDS =
            Set.of("name", "displayName", "description", "parentId");

    private final AssertionRepository assertionRepository;
    private final CapabilityRepository capabilityRepository;
    private final AiCurationEventRepository eventRepository;
    private final CurationService curationService;
    private final GroundingVerifier groundingVerifier;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DraftCommitService(AssertionRepository assertionRepository,
                              CapabilityRepository capabilityRepository,
                              AiCurationEventRepository eventRepository,
                              CurationService curationService,
                              GroundingVerifier groundingVerifier,
                              ObjectMapper mapper,
                              Clock clock) {
        this.assertionRepository = assertionRepository;
        this.capabilityRepository = capabilityRepository;
        this.eventRepository = eventRepository;
        this.curationService = curationService;
        this.groundingVerifier = groundingVerifier;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------- commit

    /**
     * Accept one generative draft (spec §5.3 steps 1-5): guards →
     * grounding re-verification → materialization → overrides-after-asserted
     * → ACCEPT event with {@code created_entity_id} in the SAME transaction.
     */
    @Governed(actionType = "capability.commit_draft")
    @Transactional
    public DraftCommitResult commitDraft(UUID assertionId, JsonNode overrides,
                                         String idempotencyKey, String actorLabel,
                                         String actorPersonaId) {
        requireIdempotencyKey(idempotencyKey);
        Assertion assertion = loadDraftAssertion(assertionId);

        // Prior-curation check (spec §5.3 step 1): already-accepted →
        // idempotent 200 with the existing capability; accepted-vs-reject
        // contradiction → 409.
        AiCurationEvent last = lastEvent(assertionId);
        if (last != null && last.getAction() == CurationAction.ACCEPT) {
            Capability existing = capabilityRepository.findById(last.getCreatedEntityId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Draft " + assertionId + " was accepted as capability "
                                    + last.getCreatedEntityId() + " but the row no longer exists"));
            return new DraftCommitResult(existing, last.getOverriddenFields(), true);
        }
        if (last != null && last.getAction() == CurationAction.REJECT) {
            throw new DiagnosticConflictException(
                    DiagnosticConflictException.Code.CURATION_CONFLICT, assertion.getDomainId(),
                    "Draft " + assertionId + " was REJECTED — a rejected draft cannot be "
                            + "committed (re-generate instead; the rejection event is the "
                            + "durable record)");
        }

        // Spec §5.3 step 2: grounding RE-VERIFIED at materialization time —
        // EVERY cited evidence id must still resolve in-domain.
        reverifyGrounding(assertion);

        // Steps 3-4: materialize, overrides AFTER asserted fields.
        ObjectNode applied = assertedFields(assertion);
        ObjectNode delta = applyOverrides(applied, overrides);
        Capability capability = materialize(assertion, applied, actorLabel);

        // Step 5: the ACCEPT event in the same tx, through the one append path.
        AiCurationEvent event = curationService.appendEvent(
                CurationSubjectKind.DRAFT, assertionId, assertion.getDomainId(),
                CurationAction.ACCEPT, null,
                delta.isEmpty() ? null : delta, capability.getId(), null,
                idempotencyKey, actorLabel, actorPersonaId, actorLabel);
        log.info("Draft {} materialized as Capability {} (DRAFT) by {} [event={}]",
                assertionId, capability.getId(), event.getActorLabel(), event.getId());
        return new DraftCommitResult(capability, delta.isEmpty() ? null : delta, false);
    }

    // ------------------------------------------------------------- reject

    /**
     * Reject one generative draft (spec §5.3): curation event ONLY — no
     * entity row exists or is created; rationale service-enforced NOT NULL;
     * 200, never error-colored. Double-reject is idempotent; rejecting an
     * ACCEPTED draft is a 409 contradiction.
     */
    @Governed(actionType = "capability.reject_draft")
    @Transactional
    public CurationDecision rejectDraft(UUID assertionId, String rationale,
                                        String idempotencyKey, String actorLabel,
                                        String actorPersonaId) {
        requireIdempotencyKey(idempotencyKey);
        Assertion assertion = loadDraftAssertion(assertionId);
        AiCurationEvent last = lastEvent(assertionId);
        if (last != null && last.getAction() == CurationAction.ACCEPT) {
            throw new DiagnosticConflictException(
                    DiagnosticConflictException.Code.CURATION_CONFLICT, assertion.getDomainId(),
                    "Draft " + assertionId + " was already ACCEPTED as capability "
                            + last.getCreatedEntityId() + " — rejecting it now is a "
                            + "contradiction (curate the capability instead)");
        }
        if (last != null && last.getAction() == CurationAction.REJECT) {
            // Idempotent replay of the rejection — original result, no new event,
            // no re-validation of the body (mirrors the finding replay lane).
            return CurationDecision.of(last, CurationStatus.REJECTED, true);
        }
        if (rationale == null || rationale.isBlank()) {
            throw new RationaleRequiredException(
                    "Rejecting a draft requires a rationale — the append-only curation audit "
                            + "records WHY a named human rejected the AI proposal (spec §5.3/§9)");
        }
        AiCurationEvent event = curationService.appendEvent(
                CurationSubjectKind.DRAFT, assertionId, assertion.getDomainId(),
                CurationAction.REJECT, rationale, null, null, null,
                idempotencyKey, actorLabel, actorPersonaId, actorLabel);
        return CurationService.freshDecision(event, CurationStatus.REJECTED, Instant.now(clock));
    }

    // ------------------------------------------------------------- guards

    /** Spec §5.3 step 1 guards: the assertion exists AND is a generative creation proposal. */
    private Assertion loadDraftAssertion(UUID assertionId) {
        Assertion assertion = assertionRepository.findById(assertionId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Assertion not found with id: " + assertionId));
        if (assertion.getProducedBy() != ProducedBy.AI_GENERATIVE
                || !PREDICATE_PROPOSES_CREATION.equals(assertion.getPredicate())) {
            throw new IllegalArgumentException("Assertion " + assertionId
                    + " is not a generative draft (producedBy=" + assertion.getProducedBy()
                    + ", predicate='" + assertion.getPredicate()
                    + "') — only AI_GENERATIVE 'proposes_creation' assertions materialize"
                    + " (spec §5.3)");
        }
        return assertion;
    }

    /**
     * Spec §5.3 step 2: EVERY {@code assertion.evidenceIds} entry must still
     * resolve to an in-domain Evidence row — stricter than the finding lane's
     * ≥1 rule, because materialization mints a real entity from the claim. A
     * draft citing nothing (or citing malformed ids) has no verifiable
     * grounding left and equally fails.
     */
    private void reverifyGrounding(Assertion assertion) {
        List<UUID> cited = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        JsonNode citedNode = assertion.getEvidenceIds();
        if (citedNode != null && citedNode.isArray()) {
            for (JsonNode entry : citedNode) {
                try {
                    cited.add(UUID.fromString(entry.asText()));
                } catch (IllegalArgumentException notAUuid) {
                    malformed.add(entry.asText());
                }
            }
        }
        if (cited.isEmpty() || !malformed.isEmpty()) {
            throw groundingLost(assertion, "cites no resolvable evidence ids"
                    + (malformed.isEmpty() ? "" : " (malformed: " + malformed + ")"));
        }
        Set<UUID> resolved = groundingVerifier
                .resolveInDomain(cited, assertion.getDomainId()).keySet();
        Set<UUID> missing = new LinkedHashSet<>(cited);
        missing.removeAll(resolved);
        if (!missing.isEmpty()) {
            throw groundingLost(assertion, "evidence " + missing
                    + " no longer resolves in-domain");
        }
    }

    private DiagnosticConflictException groundingLost(Assertion assertion, String detail) {
        return new DiagnosticConflictException(
                DiagnosticConflictException.Code.DRAFT_GROUNDING_LOST, assertion.getDomainId(),
                "Draft " + assertion.getId() + " " + detail
                        + " — the proposal is stale against current evidence and is never "
                        + "silently materialized (spec §5.3 step 2)");
    }

    // ------------------------------------------------------ materialization

    /** The allowlisted {@code proposedFields} stored on the assertion at proposal time. */
    private ObjectNode assertedFields(Assertion assertion) {
        JsonNode object;
        try {
            object = mapper.readTree(assertion.getObject());
        } catch (JsonProcessingException corrupted) {
            throw new IllegalStateException("assertion.object of draft " + assertion.getId()
                    + " is not parseable JSON — not a materializable proposedFields payload",
                    corrupted);
        }
        ObjectNode applied = mapper.createObjectNode();
        if (object != null && object.isObject()) {
            for (String field : PROPOSABLE_FIELDS) {
                JsonNode value = object.get(field);
                if (value != null && !value.isNull()) {
                    applied.set(field, value);
                }
            }
        }
        return applied;
    }

    /**
     * Spec §5.3 step 4: human {@code overrides} apply AFTER the asserted
     * fields, restricted to the proposable allowlist; the returned delta
     * (field → applied value, only where the override actually changed the
     * asserted value) is recorded on the ACCEPT event. Keys outside the
     * allowlist are dropped — the same structural P6 discipline the mode
     * service applies to the LLM.
     */
    private ObjectNode applyOverrides(ObjectNode applied, JsonNode overrides) {
        ObjectNode delta = mapper.createObjectNode();
        if (overrides == null || !overrides.isObject()) {
            return delta;
        }
        for (String field : PROPOSABLE_FIELDS) {
            JsonNode value = overrides.get(field);
            if (value == null) {
                continue;
            }
            JsonNode asserted = applied.get(field);
            if (asserted != null && asserted.equals(value)) {
                continue; // no-op override — not a human edit, not provenance
            }
            if (value.isNull()) {
                applied.remove(field);
            } else {
                applied.set(field, value);
            }
            delta.set(field, value);
        }
        return delta;
    }

    /** Spec §5.3 step 3: {@code Capability(status=DRAFT, aiGenerated=true)} from the applied fields. */
    private Capability materialize(Assertion assertion, ObjectNode applied, String actorLabel) {
        String name = text(applied, "name");
        String displayName = text(applied, "displayName");
        if (name == null && displayName == null) {
            throw new IllegalArgumentException("Draft " + assertion.getId()
                    + " proposes neither name nor displayName (after overrides) — a Capability "
                    + "cannot be materialized without one (columns are NOT NULL)");
        }

        Capability capability = new Capability();
        capability.setDomainId(assertion.getDomainId());
        capability.setName(name != null ? name : displayName);
        capability.setDisplayName(displayName != null ? displayName : name);
        capability.setDescription(text(applied, "description"));
        capability.setStatus(CapabilityStatus.DRAFT);
        capability.setAiGenerated(true);
        capability.setCreatedBy(actorLabel == null || actorLabel.isBlank() ? "unknown" : actorLabel);

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("assertionId", assertion.getId().toString());
        JsonNode context = assertion.getProducedByContext();
        if (context != null && context.hasNonNull("synthesisRunId")) {
            metadata.put("synthesisRunId", context.get("synthesisRunId").asText());
        }

        // parentId→parentCapabilityId rename (mode-service javadoc contract).
        // Unresolvable/malformed/out-of-domain parent → committed parentless,
        // metadata.unresolvedParent=true — surfaced, never silently dropped.
        Capability parent = resolveParent(text(applied, "parentId"), assertion.getDomainId());
        if (parent != null) {
            capability.setParentCapabilityId(parent.getId());
            capability.setLevel(parent.getLevel() + 1);
            capability.setSequenceOrder(capabilityRepository
                    .findSiblingsUnderParent(assertion.getDomainId(), parent.getId()).size());
        } else {
            capability.setLevel(0);
            capability.setSequenceOrder(capabilityRepository
                    .findRootSiblings(assertion.getDomainId()).size());
            if (applied.hasNonNull("parentId")) {
                metadata.put("unresolvedParent", true);
            }
        }
        capability.setMetadata(metadata);
        return capabilityRepository.save(capability);
    }

    private Capability resolveParent(String parentIdText, UUID domainId) {
        if (parentIdText == null || parentIdText.isBlank()) {
            return null;
        }
        UUID parentId;
        try {
            parentId = UUID.fromString(parentIdText);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        return capabilityRepository.findById(parentId)
                .filter(parent -> domainId.equals(parent.getDomainId()))
                .orElse(null);
    }

    // ------------------------------------------------------------- helpers

    /** Latest curation event of the draft — the lookup half of the spec §5.3 step-1 guard. */
    private AiCurationEvent lastEvent(UUID assertionId) {
        List<AiCurationEvent> events = eventRepository
                .findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(CurationSubjectKind.DRAFT, assertionId);
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    private static String text(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }
}
