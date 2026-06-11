package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.engine.cascade.CascadeDraftService;
import com.meridian.substrate.engine.cascade.read.CascadeDraftDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The engine's ONLY orchestrator touchpoint on the wire (engine-spec
 * cascade.md §6.3/§7; ADR-001 D14): {@code POST /cascade-drafts/{id}/suggest}.
 *
 * <p>Registered ONLY when {@code substrate.orchestrator.enabled=true}. With
 * the flag off this controller does not exist, the route resolves to nothing
 * and the request answers <b>404</b> through the standard envelope — the
 * hidden-affordance contract (degraded mode is identical by construction; no
 * other conditional path is permitted). A pinned §12 test #11 guards both
 * halves.
 *
 * <p>Returns <b>202</b> + the draft with its AI-assist plumbing status:
 * {@code suggestionState=pending} when the dispatch was accepted, or
 * {@code error} when the budget preflight refused / dispatch failed — a
 * non-event for governance, the human authoring path continues either way
 * (never an error envelope; only 404 unknown id / 409 non-OPEN apply).
 */
@RestController
@RequestMapping("/api/v1/cascade-drafts")
@ConditionalOnProperty(name = "substrate.orchestrator.enabled", havingValue = "true")
public class CascadeDraftSuggestController {

    private final CascadeDraftService draftService;

    public CascadeDraftSuggestController(CascadeDraftService draftService) {
        this.draftService = draftService;
    }

    /** §6.3 {@code /suggest} → 202 with {@code suggestionState} reflecting the §7 flow. */
    @PostMapping("/{id}/suggest")
    public ResponseEntity<CascadeDraftDto> suggest(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CascadeDraftDto.from(draftService.suggest(id)));
    }
}
