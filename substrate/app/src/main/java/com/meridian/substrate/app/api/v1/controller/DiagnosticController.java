package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.StartDiagnosticRunRequest;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.engine.diagnostic.DiagnosticRunService;
import com.meridian.substrate.engine.diagnostic.ProvenanceWalkService;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunDetail;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunSummary;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticVerifyReport;
import com.meridian.substrate.engine.diagnostic.read.FindingProvenance;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Diagnostic-engine REST surface (engine-spec diagnostic.md §10.1).
 *
 * <p>Build-order step 2 shipped the audit-defensibility read model FIRST
 * (ADR-004 D5/D15): the per-finding provenance walk and the
 * {@code /diagnostics/verify} chain-integrity sweep, user-runnable against
 * DemoPack data before any generation existed. Build-order step 4 adds the
 * run lifecycle: start (202; 200 plan body when dryRun; 409
 * {@code DIAGNOSTIC_RUN_ACTIVE}), list, detail, resume, cancel.
 *
 * <p>Color semantics (spec non-negotiable #3; test plan #17): cancel returns
 * 200 ALWAYS; a ZERO_YIELD run renders through run detail as a loud PRODUCT
 * outcome, never an error mapping.
 */
@RestController
@RequestMapping("/api/v1")
public class DiagnosticController {

    private final ProvenanceWalkService provenanceWalkService;
    private final DiagnosticRunService runService;
    private final PersonaContext personaContext;

    public DiagnosticController(ProvenanceWalkService provenanceWalkService,
                                DiagnosticRunService runService,
                                PersonaContext personaContext) {
        this.provenanceWalkService = provenanceWalkService;
        this.runService = runService;
        this.personaContext = personaContext;
    }

    // ------------------------------------------------------- run lifecycle

    /**
     * Start a diagnostic run (spec §4.2/§10.1): 202 {@code {runId}}; 200 with
     * the deterministic batch plan when {@code dryRun=true}; 409
     * {@code DIAGNOSTIC_RUN_ACTIVE} when the domain already has a
     * PLANNED/RUNNING diagnostic run. Idempotent replay of the same
     * {@code (domainId, idempotencyKey)} returns the original run id.
     */
    @PostMapping("/diagnostics/runs")
    public ResponseEntity<?> startRun(@Valid @RequestBody StartDiagnosticRunRequest request) {
        DiagnosticRunService.StartResult result = runService.startRun(
                new DiagnosticRunService.StartCommand(
                        request.domainId(),
                        request.scopes(),
                        request.targetEntityIds(),
                        request.batchSize(),
                        request.requiredMinFindings(),
                        request.executor(),
                        Boolean.TRUE.equals(request.dryRun()),
                        request.idempotencyKey(),
                        actorLabel(),
                        null));
        if (result.dryRun()) {
            return ResponseEntity.ok(result.plan());
        }
        return ResponseEntity.accepted().body(Map.of(
                "runId", result.runId(),
                "replayed", result.replayed()));
    }

    /** Run summaries, newest first (spec §10.1). */
    @GetMapping("/diagnostics/runs")
    public java.util.List<DiagnosticRunSummary> listRuns(@RequestParam("domainId") UUID domainId) {
        return runService.listRuns(domainId);
    }

    /** Status, counts, batch ledger, filter histogram, explanation, token/cost totals (spec §10.1). */
    @GetMapping("/diagnostics/runs/{id}")
    public DiagnosticRunDetail getRun(@PathVariable("id") UUID id) {
        return runService.getRun(id);
    }

    /** Re-executes PENDING / FAILED / stale-RUNNING batches; terminal runs are an idempotent no-op 200 (spec §4.4). */
    @PostMapping("/diagnostics/runs/{id}/resume")
    public DiagnosticRunDetail resume(@PathVariable("id") UUID id) {
        return runService.resume(id, null);
    }

    /** Operator cancel — 200, neutral, never error-colored (spec §4.4). */
    @PostMapping("/diagnostics/runs/{id}/cancel")
    public DiagnosticRunDetail cancel(@PathVariable("id") UUID id) {
        return runService.cancel(id);
    }

    // ------------------------------------------------------- read model (step 2)

    /**
     * The full P6 audit walk for one finding (spec §6.1): finding (incl.
     * curation projection) → assertion → evidence[] → documentRegistry[] →
     * ingestRuns[] → readerRegistrations[] → curationEvents[], plus
     * {@code chainComplete} (false on ANY dangling hop, hops named),
     * {@code groundingMode} ({@code EXISTENCE_ONLY} for all v1 AI findings —
     * §6.2 honesty posture) and {@code fixtureGrounded}
     * ({@code extractor='seed'} chains).
     */
    @GetMapping("/findings/{id}/provenance")
    public FindingProvenance provenance(@PathVariable("id") UUID id) {
        return provenanceWalkService.walk(id);
    }

    /**
     * Chain-integrity sweep over every {@code aiGenerated=true} finding of
     * the domain (spec §6.1): broken chains (named missing hop) and
     * fixture-grounded chains as distinct classes, plus groundingMode counts.
     */
    @GetMapping("/diagnostics/verify")
    public DiagnosticVerifyReport verify(@RequestParam("domainId") UUID domainId) {
        return provenanceWalkService.verify(domainId);
    }

    /** Trigger-actor label recorded in run metadata (S9: spoofable persona headers until Phase-5 OIDC). */
    private String actorLabel() {
        if (personaContext.getPersonaLabel() != null && !personaContext.getPersonaLabel().isBlank()) {
            return personaContext.getPersonaLabel();
        }
        return personaContext.getPersonaLevel();
    }
}
