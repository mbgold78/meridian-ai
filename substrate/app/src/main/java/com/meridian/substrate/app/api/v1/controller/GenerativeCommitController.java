package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.CommitDraftRequest;
import com.meridian.substrate.app.api.v1.request.RejectDraftRequest;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.engine.diagnostic.DraftCommitService;
import com.meridian.substrate.engine.diagnostic.read.CurationDecision;
import com.meridian.substrate.engine.diagnostic.read.DraftCommitResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The generative commit step — DraftEntity → Capability (engine-spec
 * diagnostic.md §5.3/§10.1; ADR-004 D9). Sibling of
 * {@link GenerativeModeController} (which PROPOSES drafts and persists only
 * Assertions); this controller is where a named human materializes or rejects
 * ONE draft at a time — there is no bulk-accept verb, structurally
 * (P6/doc-07; test plan #8).
 *
 * <ul>
 *   <li>{@code POST /commit} → <b>201</b> {@code Capability(status=DRAFT,
 *       aiGenerated=true)}; an already-accepted draft replays idempotently as
 *       <b>200</b> with the existing capability; a rejected draft → 409
 *       {@code CURATION_CONFLICT}; stale evidence → 409
 *       {@code DRAFT_GROUNDING_LOST} (grounding is RE-VERIFIED at
 *       materialization, spec §5.3 step 2).</li>
 *   <li>{@code POST /reject} → <b>200</b> — an audit event only, no entity
 *       row exists or is created; rationale service-enforced (400
 *       {@code RATIONALE_REQUIRED}); never error-colored
 *       (non-negotiable #3).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ai/generative")
public class GenerativeCommitController {

    private final DraftCommitService draftCommitService;
    private final PersonaContext personaContext;

    public GenerativeCommitController(DraftCommitService draftCommitService,
                                      PersonaContext personaContext) {
        this.draftCommitService = draftCommitService;
        this.personaContext = personaContext;
    }

    /** Materialize one draft: 201 fresh, 200 idempotent replay (spec §10.1). */
    @PostMapping("/commit")
    public ResponseEntity<Capability> commit(@Valid @RequestBody CommitDraftRequest request) {
        DraftCommitResult result = draftCommitService.commitDraft(
                request.assertionId(), request.overrides(), request.idempotencyKey(),
                actorLabel(), personaContext.getPersonaId());
        return result.replayed()
                ? ResponseEntity.ok(result.capability())
                : ResponseEntity.status(201).body(result.capability());
    }

    /** Reject one draft: 200 ALWAYS on success — rejection is a product outcome. */
    @PostMapping("/reject")
    public CurationDecision reject(@Valid @RequestBody RejectDraftRequest request) {
        return draftCommitService.rejectDraft(
                request.assertionId(), request.rationale(), request.idempotencyKey(),
                actorLabel(), personaContext.getPersonaId());
    }

    /** Actor label for the audit row (S9: spoofable persona headers until Phase-5 OIDC). */
    private String actorLabel() {
        if (personaContext.getPersonaLabel() != null && !personaContext.getPersonaLabel().isBlank()) {
            return personaContext.getPersonaLabel();
        }
        return personaContext.getPersonaLevel();
    }
}
