package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.AcceptFindingRequest;
import com.meridian.substrate.app.api.v1.request.RejectFindingRequest;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.engine.diagnostic.CurationService;
import com.meridian.substrate.engine.diagnostic.read.CurationDecision;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Finding curation verbs (engine-spec diagnostic.md §9/§10.1) — strictly
 * per-item: one finding id in the path, one event row per decision. There is
 * NO bulk verb on this surface, structurally (P6/doc-07; test plan #8;
 * EXTENSION POINT(diagnostic-3) is Mike's product call, ADR-004 follow-up #2).
 *
 * <p>Color semantics (non-negotiable #3; test plan #17): REJECT returns
 * <b>200</b> with an empty {@code errors} array — rejection is a product
 * outcome. The only error-shaped responses here are input failures
 * (400 {@code RATIONALE_REQUIRED}) and state contradictions
 * (409 {@code CURATION_CONFLICT}).
 *
 * <p>The S3 compensating control lives in the UX contract: the accept
 * affordance MUST render the cited evidence inline via
 * {@code GET /findings/{id}/provenance} (spec §6.2) — this verb records that
 * a named human verified semantic support. Actor identity is spoofable
 * persona headers until Phase-5 OIDC (S9).
 */
@RestController
@RequestMapping("/api/v1/findings")
public class FindingCurationController {

    private final CurationService curationService;
    private final PersonaContext personaContext;

    public FindingCurationController(CurationService curationService,
                                     PersonaContext personaContext) {
        this.curationService = curationService;
        this.personaContext = personaContext;
    }

    /** ACCEPT — legal from PENDING_REVIEW and from REJECTED (recourse); 200 with the decision. */
    @PostMapping("/{id}/accept")
    public CurationDecision accept(@PathVariable("id") UUID id,
                                   @Valid @RequestBody AcceptFindingRequest request) {
        return curationService.accept(id, request.rationale(), request.idempotencyKey(),
                actorLabel(), personaContext.getPersonaId());
    }

    /** REJECT — rationale service-enforced; 200 ALWAYS on success, never error-colored. */
    @PostMapping("/{id}/reject")
    public CurationDecision reject(@PathVariable("id") UUID id,
                                   @Valid @RequestBody RejectFindingRequest request) {
        return curationService.reject(id, request.rationale(), request.idempotencyKey(),
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
