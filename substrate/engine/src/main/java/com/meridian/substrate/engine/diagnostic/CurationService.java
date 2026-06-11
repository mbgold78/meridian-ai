package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.exception.RationaleRequiredException;
import com.meridian.substrate.engine.diagnostic.read.CurationDecision;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Human curation over committed AI findings + the unified append-only audit
 * path (engine-spec diagnostic.md §9; ADR-004 D4/D7/D10).
 *
 * <p>Truth lives in {@code ai_curation_events}; the Finding columns
 * ({@code curation_status, curated_at, curated_by_label}) are the
 * last-event-wins projection. History is NEVER deleted: ACCEPT-after-REJECT
 * (and the symmetric REJECT-after-ACCEPT mis-click recovery, D4
 * "append another event; last event wins") appends a new attributed row and
 * flips the projection. REJECT requires a rationale (400
 * {@code RATIONALE_REQUIRED}) and returns 200 — rejection is a product
 * outcome, never error-colored (non-negotiable #3). Replay of a spent
 * idempotency key returns the ORIGINAL result, no new event
 * ({@code uq_curation_idem} is the DB backstop for races — test plan #10).
 *
 * <p>The curation ACCEPT is the S3 compensating control (spec §6.2; ADR-004
 * D7): evidence citations are existence-verified; semantic support is
 * human-verified at acceptance — the accept affordance MUST render the cited
 * evidence inline (the §6.1 provenance walk is that chain), and the ACCEPT
 * event is the durable record a named human did so. Actor identity is
 * spoofable persona headers until Phase-5 OIDC (S9) — stated in any
 * compliance conversation.
 *
 * <p>DRAFT-kind events are written by {@link DraftCommitService} through this
 * service's append path — one audit table, one discipline (spec §9).
 * SUPERSEDE events are system-authored at run finalization ONLY
 * ({@link #supersedePriorPendingFindings}, called inside the
 * {@link DiagnosticRunService} finalization transaction); there is NO
 * operator supersede verb — the method is deliberately package-private, so
 * the absence is structural, not policed.
 *
 * <p>EXTENSION POINT(diagnostic-3): batch-accept curation affordance —
 * structurally absent; per-item events only. No bulk verb exists on this
 * surface or any other (P6/doc-07; test plan #8 pins compile-time absence);
 * even if Mike grants the affordance (ADR-004 follow-up #2) it would emit one
 * event row per item. Flag, don't invent.
 *
 * <p>EXTENSION POINT(diagnostic-7): deterministic structural validators. v1
 * accepts LLM STRUCTURAL findings per the pinned phase2a contract, so
 * evidence-less STRUCTURAL rows transit this same human gate; replacing them
 * with deterministic validators (more P7-aligned) awaits the first-corpus
 * review. Flag, don't invent.
 */
@Service
public class CurationService {

    private static final Logger log = LoggerFactory.getLogger(CurationService.class);

    /** Spec §4.5 actor label on system-authored SUPERSEDE events. */
    public static final String SYSTEM_ACTOR = "system:diagnostic";

    private static final Set<CurationStatus> ACCEPTABLE_FROM =
            Set.of(CurationStatus.PENDING_REVIEW, CurationStatus.REJECTED);
    private static final Set<CurationStatus> REJECTABLE_FROM =
            Set.of(CurationStatus.PENDING_REVIEW, CurationStatus.ACCEPTED);

    private final FindingRepository findingRepository;
    private final AiCurationEventRepository eventRepository;
    private final SynthesisRunRepository runRepository;
    private final Clock clock;

    public CurationService(FindingRepository findingRepository,
                           AiCurationEventRepository eventRepository,
                           SynthesisRunRepository runRepository,
                           Clock clock) {
        this.findingRepository = findingRepository;
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------- accept

    /**
     * ACCEPT a committed finding (spec §9): legal from
     * {@code PENDING_REVIEW} and from {@code REJECTED} (ACCEPT-after-REJECT
     * is legal recourse); appends the attributed event and projects
     * {@code ACCEPTED} onto the row. Rationale is optional on ACCEPT.
     */
    @Governed(actionType = "finding.accept")
    @Transactional
    public CurationDecision accept(UUID findingId, String rationale, String idempotencyKey,
                                   String actorLabel, String actorPersonaId) {
        requireIdempotencyKey(idempotencyKey);
        Finding finding = loadFinding(findingId);
        CurationDecision replayed = replayOf(CurationSubjectKind.FINDING, findingId,
                idempotencyKey, CurationAction.ACCEPT, finding.getDomainId());
        if (replayed != null) {
            return replayed;
        }
        requireStatusIn(finding, ACCEPTABLE_FROM, "accept");
        AiCurationEvent event = appendEvent(CurationSubjectKind.FINDING, findingId,
                finding.getDomainId(), CurationAction.ACCEPT, blankToNull(rationale),
                null, null, null, idempotencyKey, actorLabel, actorPersonaId, actorLabel);
        Instant curatedAt = project(finding, CurationStatus.ACCEPTED, event);
        return freshDecision(event, CurationStatus.ACCEPTED, curatedAt);
    }

    // ------------------------------------------------------------- reject

    /**
     * REJECT a committed finding (spec §9): rationale service-enforced NOT
     * NULL (400 {@code RATIONALE_REQUIRED} when absent); the row STAYS —
     * default list queries exclude it, {@code includeRejected=true} restores
     * it. 200, never error-colored. Legal from {@code PENDING_REVIEW} and
     * from {@code ACCEPTED} (the D4 symmetric mis-click recovery).
     */
    @Governed(actionType = "finding.reject")
    @Transactional
    public CurationDecision reject(UUID findingId, String rationale, String idempotencyKey,
                                   String actorLabel, String actorPersonaId) {
        requireIdempotencyKey(idempotencyKey);
        Finding finding = loadFinding(findingId);
        CurationDecision replayed = replayOf(CurationSubjectKind.FINDING, findingId,
                idempotencyKey, CurationAction.REJECT, finding.getDomainId());
        if (replayed != null) {
            return replayed;
        }
        if (rationale == null || rationale.isBlank()) {
            throw new RationaleRequiredException(
                    "REJECT requires a rationale — the append-only curation audit records "
                            + "WHY a named human rejected the AI claim (spec §9)");
        }
        requireStatusIn(finding, REJECTABLE_FROM, "reject");
        AiCurationEvent event = appendEvent(CurationSubjectKind.FINDING, findingId,
                finding.getDomainId(), CurationAction.REJECT, rationale,
                null, null, null, idempotencyKey, actorLabel, actorPersonaId, actorLabel);
        Instant curatedAt = project(finding, CurationStatus.REJECTED, event);
        return freshDecision(event, CurationStatus.REJECTED, curatedAt);
    }

    // -------------------------------------------------------- supersession

    /**
     * Supersession-at-finalization (spec §4.5; ADR-004 D3) — called ONLY from
     * inside the {@link DiagnosticRunService} finalization transaction, iff
     * the terminal status is {@code COMPLETED} or
     * {@code COMPLETED_WITH_ERRORS}: prior findings of the same
     * {@code (domainId, scope-set)} still {@code PENDING_REVIEW} and NOT
     * created by the superseding run are marked {@code SUPERSEDED}, one
     * append-only event each ({@code action=SUPERSEDE},
     * {@code synthesis_run_id} = the superseding run,
     * {@code actor_label='system:diagnostic'}). ACCEPTED/REJECTED rows are
     * never touched; ZERO_YIELD/FAILED/CANCELLED runs never reach this method
     * — a deficient run must not wipe the prior pending set.
     *
     * <p>Scope-set matching is EXACT (sorted-set equality) against the origin
     * run's recorded {@code metadata.scopes}; a pending finding whose origin
     * run cannot be resolved to a diagnostic run with a recorded scope set is
     * conservatively left alone (never superseded on a guess).
     *
     * <p>EXTENSION POINT(diagnostic-11): supersession scope normalization —
     * exact match only; overlapping scopes can accumulate pendings (ADR-004
     * follow-up #7). Flag, don't invent.
     *
     * @return ids of the findings superseded (the run's
     *         {@code metadata.supersededFindingIds})
     */
    List<UUID> supersedePriorPendingFindings(SynthesisRun supersedingRun, Set<String> scopeSet) {
        Set<String> normalized = new TreeSet<>(scopeSet);
        Map<UUID, Boolean> matchByOriginRun = new HashMap<>();
        List<UUID> superseded = new ArrayList<>();

        for (Finding finding : findingRepository.findByDomainIdAndCurationStatus(
                supersedingRun.getDomainId(), CurationStatus.PENDING_REVIEW)) {
            UUID originRunId = finding.getSynthesisRunId();
            if (originRunId == null || originRunId.equals(supersedingRun.getId())) {
                continue; // operator-attached or this run's own rows — never superseded
            }
            boolean match = matchByOriginRun.computeIfAbsent(originRunId,
                    id -> scopeSetMatches(id, normalized));
            if (!match) {
                continue;
            }
            AiCurationEvent event = appendEvent(CurationSubjectKind.FINDING, finding.getId(),
                    supersedingRun.getDomainId(), CurationAction.SUPERSEDE, null,
                    null, null, supersedingRun.getId(),
                    "supersede:" + supersedingRun.getId(), SYSTEM_ACTOR, null,
                    "diagnostic-engine");
            project(finding, CurationStatus.SUPERSEDED, event);
            superseded.add(finding.getId());
        }
        if (!superseded.isEmpty()) {
            log.info("Superseded {} prior PENDING_REVIEW finding(s) of (domain={}, scopes={}) "
                            + "at finalization of run {}",
                    superseded.size(), supersedingRun.getDomainId(), normalized,
                    supersedingRun.getId());
        }
        return superseded;
    }

    /** Exact (domain, scope-set) match against the origin diagnostic run's recorded scopes. */
    private boolean scopeSetMatches(UUID originRunId, Set<String> scopeSet) {
        return runRepository.findById(originRunId)
                .filter(run -> DiagnosticRunService.RUN_TYPE_DIAGNOSTIC.equals(run.getRunType()))
                .map(run -> scopeSetOf(run.getMetadata()).equals(scopeSet))
                .orElse(false);
    }

    /** Sorted scope set recorded in run metadata at start time (spec §4.2 step 2). */
    static Set<String> scopeSetOf(JsonNode runMetadata) {
        Set<String> scopes = new TreeSet<>();
        if (runMetadata != null && runMetadata.path("scopes").isArray()) {
            runMetadata.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        }
        return scopes;
    }

    // ------------------------------------------------- shared append path

    /**
     * THE one append path onto {@code ai_curation_events} (spec §9: one audit
     * table, one discipline) — used by the finding verbs above, the
     * finalization supersession, and {@link DraftCommitService}'s DRAFT-kind
     * events. Insert-only: rows are never loaded-and-mutated (append-only;
     * PG V7_1 trigger is the DB backstop).
     */
    AiCurationEvent appendEvent(CurationSubjectKind subjectKind, UUID subjectId, UUID domainId,
                                CurationAction action, String rationale,
                                JsonNode overriddenFields, UUID createdEntityId,
                                UUID synthesisRunId, String idempotencyKey,
                                String actorLabel, String actorPersonaId, String createdBy) {
        AiCurationEvent event = new AiCurationEvent();
        event.setSubjectKind(subjectKind);
        event.setSubjectId(subjectId);
        event.setDomainId(domainId);
        event.setAction(action);
        event.setRationale(rationale);
        event.setOverriddenFields(overriddenFields);
        event.setCreatedEntityId(createdEntityId);
        event.setSynthesisRunId(synthesisRunId);
        event.setIdempotencyKey(idempotencyKey);
        event.setActorLabel(actorLabel == null || actorLabel.isBlank() ? "unknown" : actorLabel);
        event.setActorPersonaId(actorPersonaId);
        event.setCreatedBy(createdBy);
        return eventRepository.save(event);
    }

    /**
     * Idempotency replay lane (spec §9): a spent key returns the ORIGINAL
     * result, no new event. The SAME key replayed with a DIFFERENT action is
     * a contradiction → 409 {@code CURATION_CONFLICT}.
     */
    CurationDecision replayOf(CurationSubjectKind subjectKind, UUID subjectId,
                              String idempotencyKey, CurationAction requestedAction,
                              UUID domainId) {
        Optional<AiCurationEvent> existing = eventRepository
                .findBySubjectKindAndSubjectIdAndIdempotencyKey(subjectKind, subjectId, idempotencyKey);
        if (existing.isEmpty()) {
            return null;
        }
        AiCurationEvent event = existing.get();
        if (event.getAction() != requestedAction) {
            throw new DiagnosticConflictException(
                    DiagnosticConflictException.Code.CURATION_CONFLICT, domainId,
                    "Idempotency key '" + idempotencyKey + "' was already spent on "
                            + event.getAction() + " for this " + subjectKind
                            + " — it cannot replay as " + requestedAction);
        }
        return CurationDecision.of(event, statusOf(event.getAction()), true);
    }

    // ------------------------------------------------------------- helpers

    private Finding loadFinding(UUID findingId) {
        return findingRepository.findById(findingId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Finding not found with id: " + findingId));
    }

    private void requireStatusIn(Finding finding, Set<CurationStatus> allowed, String verb) {
        CurationStatus current = finding.getCurationStatus();
        if (current == null) {
            throw new DiagnosticConflictException(
                    DiagnosticConflictException.Code.CURATION_CONFLICT, finding.getDomainId(),
                    "Finding " + finding.getId() + " is not subject to curation "
                            + "(curationStatus is NULL — operator/fixture row, spec §3.1)");
        }
        if (!allowed.contains(current)) {
            throw new DiagnosticConflictException(
                    DiagnosticConflictException.Code.CURATION_CONFLICT, finding.getDomainId(),
                    "Cannot " + verb + " finding " + finding.getId() + " from curationStatus "
                            + current + " (legal from " + allowed + ")");
        }
    }

    /** Last-event-wins projection onto the Finding row (cascade D11 denormalized-label idiom). */
    private Instant project(Finding finding, CurationStatus status, AiCurationEvent event) {
        Instant now = Instant.now(clock);
        finding.setCurationStatus(status);
        finding.setCuratedAt(now);
        finding.setCuratedByLabel(event.getActorLabel());
        findingRepository.save(finding);
        return now;
    }

    /**
     * Decision over a JUST-appended event: {@code @CreationTimestamp}
     * populates at flush, so the fresh lane carries the projection instant
     * (the replay lane uses the loaded row via {@link CurationDecision#of}).
     */
    static CurationDecision freshDecision(AiCurationEvent event, CurationStatus status,
                                          Instant curatedAt) {
        return new CurationDecision(
                event.getSubjectKind() != null ? event.getSubjectKind().name() : null,
                event.getSubjectId(), status, curatedAt, event.getActorLabel(),
                event.getId(), false);
    }

    private static CurationStatus statusOf(CurationAction action) {
        return switch (action) {
            case ACCEPT -> CurationStatus.ACCEPTED;
            case REJECT -> CurationStatus.REJECTED;
            case SUPERSEDE -> CurationStatus.SUPERSEDED;
        };
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
