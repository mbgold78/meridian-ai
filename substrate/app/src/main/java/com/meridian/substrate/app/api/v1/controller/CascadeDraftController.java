package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.CascadeDraftCreateRequest;
import com.meridian.substrate.app.api.v1.request.CascadeDraftIssueRequest;
import com.meridian.substrate.app.api.v1.request.CascadeDraftPatchRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.engine.cascade.CascadeActor;
import com.meridian.substrate.engine.cascade.CascadeDraftService;
import com.meridian.substrate.engine.cascade.CascadeLifecycleService;
import com.meridian.substrate.engine.cascade.CascadeQueryService;
import com.meridian.substrate.engine.cascade.read.CascadeDetail;
import com.meridian.substrate.engine.cascade.read.CascadeDraftDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cascade draft authoring endpoints (engine-spec cascade.md §6.3; ADR-001 D6)
 * — `{data, meta, errors}` enveloped via {@code ResponseEnvelopeAdvice},
 * kebab-case wire vocabulary (ADR D13). Deliberately operator-grade and
 * shape-neutral: leader authoring UX is gated on the queue-#24 Mike session
 * (EXTENSION POINT(cascade-5), spec §13.5) — no authoring affordances beyond
 * the spec table live here.
 *
 * <p>The §7 AI-suggest endpoint ({@code POST /{id}/suggest}) lives on the
 * separate {@link CascadeDraftSuggestController}, registered ONLY when
 * {@code substrate.orchestrator.enabled=true} — with the flag off the route
 * does not exist and answers 404 (hidden affordance, degraded mode by
 * construction; contract doc).
 *
 * <p>Issue semantics (§6.3): first promotion → <b>201</b> {@code CascadeDetail}
 * (§5.2 A1, cascade born IN_FLIGHT); re-issue of an ISSUED draft → <b>200</b>
 * with the existing cascade (idempotent, draft-row anchored, ADR D8).
 *
 * <p>Author identity rides the §5.5 persona headers (spoofable in Phase 2,
 * accepted per S9; OIDC replaces the source in Phase 5).
 */
@RestController
@RequestMapping("/api/v1/cascade-drafts")
public class CascadeDraftController {

    private final CascadeDraftService draftService;
    private final CascadeQueryService queryService;
    private final PersonaContext personaContext;

    public CascadeDraftController(CascadeDraftService draftService,
                                  CascadeQueryService queryService,
                                  PersonaContext personaContext) {
        this.draftService = draftService;
        this.queryService = queryService;
        this.personaContext = personaContext;
    }

    /** §6.3 create → 201 {@code CascadeDraftDto}. */
    @PostMapping
    public ResponseEntity<CascadeDraftDto> create(@Valid @RequestBody CascadeDraftCreateRequest request) {
        CascadeDraftDto dto = CascadeDraftDto.from(draftService.create(
                new CascadeDraftService.CreateCommand(
                        request.domainId(),
                        parseAuthoringMode(request.authoringMode()),
                        request.originalCritique(),
                        request.acceptanceCriteria(),
                        request.summary(),
                        request.affectedDomainId(),
                        request.originFindingId(),
                        request.cascadeGroupId(),
                        request.responseSlaDays(),
                        currentActor())));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /** §6.3 partial update — only while OPEN (409 otherwise). */
    @PatchMapping("/{id}")
    public CascadeDraftDto patch(@PathVariable UUID id,
                                 @Valid @RequestBody CascadeDraftPatchRequest request) {
        return CascadeDraftDto.from(draftService.patch(id,
                new CascadeDraftService.PatchCommand(
                        request.authoringMode() != null
                                ? parseAuthoringMode(request.authoringMode()) : null,
                        request.originalCritique(),
                        request.acceptanceCriteria(),
                        request.summary(),
                        request.affectedDomainId(),
                        request.originFindingId(),
                        request.cascadeGroupId(),
                        request.responseSlaDays())));
    }

    /** §6.3 read — 404 unknown id; a PENDING suggestion is refreshed on read (§7 step 3). */
    @GetMapping("/{id}")
    public CascadeDraftDto get(@PathVariable UUID id) {
        return draftService.get(id)
                .map(CascadeDraftDto::from)
                .orElseThrow(() -> new EntityNotFoundException("CascadeDraft", id));
    }

    /** §6.3 list — {@code domainId} required, {@code status} optional wire value. */
    @GetMapping
    public List<CascadeDraftDto> list(@RequestParam UUID domainId,
                                      @RequestParam(required = false) String status) {
        return draftService.list(domainId, parseStatus(status)).stream()
                .map(CascadeDraftDto::from)
                .toList();
    }

    /** §6.3 {@code /discard} — OPEN → DISCARDED, 200. */
    @PostMapping("/{id}/discard")
    public CascadeDraftDto discard(@PathVariable UUID id) {
        return CascadeDraftDto.from(draftService.discard(id));
    }

    /** §6.3 {@code /issue} — 201 {@code CascadeDetail}; idempotent re-issue → 200. */
    @PostMapping("/{id}/issue")
    public ResponseEntity<CascadeDetail> issue(@PathVariable UUID id,
                                               @Valid @RequestBody CascadeDraftIssueRequest request) {
        CascadeDraftService.IssueOutcome outcome = draftService.issue(id,
                new CascadeLifecycleService.IssueCommand(
                        request.idempotencyKey(),
                        currentActor(),
                        request.level1OwnerLabel(),
                        request.level1OwnerMemberId(),
                        request.originatorRefinedCritique()));
        CascadeDetail detail = queryService.detail(outcome.cascade().getId())
                .orElseThrow(() -> new EntityNotFoundException("Cascade", outcome.cascade().getId()));
        return ResponseEntity
                .status(outcome.reissued() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(detail);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * §5.5 persona-header actor mapping (same as {@code CascadeController}):
     * {@code X-Persona-Id} → member id when UUID-parseable;
     * {@code X-Persona-Label} → label, falling back to the persona-level
     * display so the I5 label invariant always holds.
     */
    private CascadeActor currentActor() {
        UUID memberId = null;
        String personaId = personaContext.getPersonaId();
        if (personaId != null && !personaId.isBlank()) {
            try {
                memberId = UUID.fromString(personaId.trim());
            } catch (IllegalArgumentException notAUuid) {
                // non-UUID persona ids carry no member identity — label-only actor
            }
        }
        String label = personaContext.getPersonaLabel();
        if (label == null || label.isBlank()) {
            label = personaContext.getPersonaLevel();
        }
        return new CascadeActor(memberId, label);
    }

    /** Bean validation already pinned the wire vocabulary; this is the enum hop. */
    private static CascadeAuthoringMode parseAuthoringMode(String wire) {
        return CascadeAuthoringMode.valueOf(
                wire.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    /** Optional kebab-case status filter; unknown values → 400 (wire vocabulary, spec §2). */
    private static CascadeDraftStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CascadeDraftStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid value for parameter 'status': " + raw
                    + " (expected one of " + Arrays.stream(CascadeDraftStatus.values())
                            .map(CascadeDraftStatus::wireValue)
                            .collect(Collectors.joining(", ")) + ")");
        }
    }
}
