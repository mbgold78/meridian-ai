package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cascade draft authoring (engine-spec cascade.md §6.3; ADR-001 D6): mutable
 * persistence OUTSIDE the audit trail — a draft demands nothing of anyone;
 * only human promotion via {@link #issue} creates a cascade (born IN_FLIGHT).
 * All three authoring shapes persist today (FREE_FORM / IMPERATIVE /
 * AI_SUGGESTED); the surface is deliberately operator-grade and shape-neutral.
 *
 * <p>EXTENSION POINT(cascade-5): leader authoring UX (queue #24) — entry
 * points, preview, routing suggestions, AI-edit-approve loops are all
 * undesigned; do not build authoring affordances ahead of the Mike session
 * (spec §13.5).
 *
 * <p>Drafts mutate only while OPEN; ISSUED/DISCARDED are settled (the only
 * post-ISSUED interaction is the idempotent re-issue). Mutations serialize on
 * the draft-row {@code PESSIMISTIC_WRITE} lock (the ADR D8 issuance anchor),
 * so a PATCH can never interleave with the OPEN→ISSUED flip.
 *
 * <p>AI-suggest (§7) is delegated to {@link OrchestratorSuggestClient} — an
 * optional bean ({@code substrate.orchestrator.enabled}); with it absent this
 * service is fully functional minus {@link #suggest} (degraded mode by
 * construction, ADR D14). Suggestion completion lands via non-blocking
 * <b>on-read refresh</b>: reads of a PENDING draft poll the dispatch once.
 */
@Service
public class CascadeDraftService {

    private static final Logger log = LoggerFactory.getLogger(CascadeDraftService.class);

    private final CascadeDraftRepository draftRepository;
    private final CascadeLifecycleService lifecycleService;
    private final ObjectProvider<OrchestratorSuggestClient> suggestClient;

    public CascadeDraftService(CascadeDraftRepository draftRepository,
                               CascadeLifecycleService lifecycleService,
                               ObjectProvider<OrchestratorSuggestClient> suggestClient) {
        this.draftRepository = draftRepository;
        this.lifecycleService = lifecycleService;
        this.suggestClient = suggestClient;
    }

    // ------------------------------------------------------------------ commands

    /** §6.3 {@code POST /cascade-drafts} body, engine form. */
    public record CreateCommand(UUID domainId, CascadeAuthoringMode authoringMode,
                                String originalCritique, String acceptanceCriteria, String summary,
                                UUID affectedDomainId, UUID originFindingId, UUID cascadeGroupId,
                                Integer responseSlaDays, CascadeActor author) {
    }

    /**
     * §6.3 {@code PATCH /cascade-drafts/{id}} body, engine form — partial
     * semantics: {@code null} fields are left untouched (operator-grade;
     * clearing a field is not part of the §6.3 surface).
     */
    public record PatchCommand(CascadeAuthoringMode authoringMode, String originalCritique,
                               String acceptanceCriteria, String summary, UUID affectedDomainId,
                               UUID originFindingId, UUID cascadeGroupId, Integer responseSlaDays) {
    }

    /** {@link #issue} outcome: {@code reissued} drives the §6.3 201-vs-200 split. */
    public record IssueOutcome(Cascade cascade, boolean reissued) {
    }

    // -------------------------------------------------------------------- verbs

    /** §6.3 create — FREE_FORM + IMPERATIVE fully usable now; AI_SUGGESTED persists too. */
    @Governed(actionType = GovernedActions.CASCADE_CREATE_DRAFT)
    @Transactional
    public CascadeDraft create(CreateCommand cmd) {
        if (cmd.domainId() == null || cmd.authoringMode() == null) {
            throw new IllegalArgumentException("domainId and authoringMode are required");
        }
        CascadeDraft draft = new CascadeDraft();
        draft.setDomainId(cmd.domainId());
        draft.setAuthorMemberId(cmd.author().memberId());
        draft.setAuthorLabel(cmd.author().label());
        draft.setOriginalCritique(cmd.originalCritique());
        draft.setAcceptanceCriteria(cmd.acceptanceCriteria());
        draft.setSummary(cmd.summary());
        draft.setAffectedDomainId(cmd.affectedDomainId());
        draft.setOriginFindingId(cmd.originFindingId());
        draft.setCascadeGroupId(cmd.cascadeGroupId());
        draft.setAuthoringMode(cmd.authoringMode());
        draft.setResponseSlaDays(cmd.responseSlaDays());
        draft.setStatus(CascadeDraftStatus.OPEN);
        draft.setSuggestionState(CascadeSuggestionState.NONE);
        draft = draftRepository.save(draft);
        log.info("Created cascade draft [id={}, domainId={}, mode={}]",
                draft.getId(), draft.getDomainId(), draft.getAuthoringMode());
        return draft;
    }

    /** §6.3 partial update — only while OPEN (409 otherwise). */
    @Governed(actionType = GovernedActions.CASCADE_PATCH_DRAFT)
    @Transactional
    public CascadeDraft patch(UUID draftId, PatchCommand cmd) {
        CascadeDraft draft = lockedDraft(draftId);
        requireOpen(draft, "patch");
        if (cmd.authoringMode() != null) {
            draft.setAuthoringMode(cmd.authoringMode());
        }
        if (cmd.originalCritique() != null) {
            draft.setOriginalCritique(cmd.originalCritique());
        }
        if (cmd.acceptanceCriteria() != null) {
            draft.setAcceptanceCriteria(cmd.acceptanceCriteria());
        }
        if (cmd.summary() != null) {
            draft.setSummary(cmd.summary());
        }
        if (cmd.affectedDomainId() != null) {
            draft.setAffectedDomainId(cmd.affectedDomainId());
        }
        if (cmd.originFindingId() != null) {
            draft.setOriginFindingId(cmd.originFindingId());
        }
        if (cmd.cascadeGroupId() != null) {
            draft.setCascadeGroupId(cmd.cascadeGroupId());
        }
        if (cmd.responseSlaDays() != null) {
            draft.setResponseSlaDays(cmd.responseSlaDays());
        }
        return draftRepository.save(draft);
    }

    /**
     * §6.3 read — empty for an unknown id (controller maps to 404). Writes
     * when (and only when) a PENDING suggestion refresh lands, hence not
     * read-only: on-read refresh is the chosen §7 step-3 poll mechanism.
     */
    @Ungoverned(reason = "read surface; transactional only for the §7 on-read suggestion refresh,"
            + " whose dispatch-result landing is the §4.2 PEP-3 governed apply inside"
            + " OrchestratorSuggestClient.refresh (actionType=apply_dispatch_result)")
    @Transactional
    public Optional<CascadeDraft> get(UUID draftId) {
        return draftRepository.findById(draftId).map(this::refreshIfPending);
    }

    /** §6.3 list — {@code domainId} required, {@code status} optional; newest first. */
    @Ungoverned(reason = "read surface; transactional only for the §7 on-read suggestion refresh,"
            + " whose dispatch-result landing is the §4.2 PEP-3 governed apply inside"
            + " OrchestratorSuggestClient.refresh (actionType=apply_dispatch_result)")
    @Transactional
    public List<CascadeDraft> list(UUID domainId, CascadeDraftStatus status) {
        List<CascadeDraft> drafts = status != null
                ? draftRepository.findByDomainIdAndStatus(domainId, status)
                : draftRepository.findByDomainId(domainId);
        return drafts.stream()
                .map(this::refreshIfPending)
                .sorted(Comparator.comparing(CascadeDraft::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** §6.3 {@code /discard} — OPEN → DISCARDED (409 otherwise). */
    @Governed(actionType = GovernedActions.CASCADE_DISCARD_DRAFT)
    @Transactional
    public CascadeDraft discard(UUID draftId) {
        CascadeDraft draft = lockedDraft(draftId);
        requireOpen(draft, "discard");
        draft.setStatus(CascadeDraftStatus.DISCARDED);
        log.info("Discarded cascade draft [id={}]", draftId);
        return draftRepository.save(draft);
    }

    /**
     * §6.3 {@code /suggest} → §7 flow via {@link OrchestratorSuggestClient}.
     * Only reachable through the conditionally-registered controller; the
     * defensive check below guards programmatic misuse. A draft already
     * PENDING refreshes instead of double-dispatching; NONE/READY/ERROR
     * (re)dispatches — re-suggesting over a landed or errored suggestion is a
     * legitimate operator move (the prior provenance is superseded; the human
     * promotion step is unchanged).
     */
    @Governed(actionType = GovernedActions.CASCADE_SUGGEST_DRAFT)
    @Transactional
    public CascadeDraft suggest(UUID draftId) {
        OrchestratorSuggestClient client = suggestClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "AI-suggest invoked without substrate.orchestrator.enabled=true (ADR D14)");
        }
        CascadeDraft draft = lockedDraft(draftId);
        requireOpen(draft, "suggest");
        if (draft.getSuggestionState() == CascadeSuggestionState.PENDING) {
            client.refresh(draft);
        } else {
            client.requestSuggestion(draft);
        }
        return draftRepository.save(draft);
    }

    /**
     * §6.3 {@code /issue} — human promotion (§5.2 A1) via
     * {@link CascadeLifecycleService#issue} under the draft-row lock taken
     * HERE first, so the {@code reissued} answer and the OPEN→ISSUED flip are
     * one serialized unit (re-issue of an ISSUED draft returns the existing
     * cascade — 200, idempotent; first issue → 201).
     */
    @Governed(actionType = GovernedActions.CASCADE_ISSUE)
    @Transactional
    public IssueOutcome issue(UUID draftId, CascadeLifecycleService.IssueCommand cmd) {
        CascadeDraft draft = lockedDraft(draftId);
        boolean reissued = draft.getStatus() == CascadeDraftStatus.ISSUED;
        Cascade cascade = lifecycleService.issue(draftId, cmd);
        return new IssueOutcome(cascade, reissued);
    }

    // ------------------------------------------------------------------ helpers

    private CascadeDraft lockedDraft(UUID draftId) {
        return draftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new EntityNotFoundException("CascadeDraft not found: " + draftId));
    }

    /**
     * Non-OPEN drafts are settled: 409 with the spec conflict code. For an
     * ISSUED draft the issued cascade's id rides the exception so the §6.2
     * conflict envelope can carry the current {@code CascadeDetail} in
     * {@code data} (null-safe for DISCARDED — no cascade exists).
     */
    private void requireOpen(CascadeDraft draft, String verb) {
        if (draft.getStatus() != CascadeDraftStatus.OPEN) {
            throw new CascadeConflictException(
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION,
                    draft.getIssuedCascadeId(),
                    "draft " + draft.getId() + " is " + draft.getStatus().wireValue()
                            + " — only OPEN drafts can " + verb);
        }
    }

    /** §7 step 3, on-read refresh: poll a PENDING dispatch once; persist a landing. */
    private CascadeDraft refreshIfPending(CascadeDraft draft) {
        if (draft.getSuggestionState() != CascadeSuggestionState.PENDING) {
            return draft;
        }
        OrchestratorSuggestClient client = suggestClient.getIfAvailable();
        if (client == null) {
            return draft; // orchestrator since disabled: suggestion stays honestly PENDING
        }
        return client.refresh(draft) ? draftRepository.save(draft) : draft;
    }
}
