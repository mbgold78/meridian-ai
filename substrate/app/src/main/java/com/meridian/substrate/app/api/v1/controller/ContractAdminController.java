package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.BreachAcknowledgeRequest;
import com.meridian.substrate.app.api.v1.request.BreachRemediateRequest;
import com.meridian.substrate.app.api.v1.request.BreachWaiveRequest;
import com.meridian.substrate.app.api.v1.request.ContractAttestRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.engine.contract.monitor.AttestationService;
import com.meridian.substrate.engine.contract.monitor.BreachService;
import com.meridian.substrate.engine.contract.monitor.ContractMonitorScheduler;
import com.meridian.substrate.engine.contract.read.AttestationResponse;
import com.meridian.substrate.engine.contract.read.BreachResponse;
import com.meridian.substrate.engine.contract.read.MonitorRunResponse;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Operator-grade §7.2 command endpoints OFF the {@code /contracts} root
 * (engine-spec contracts.md §7: {@code ContractAdminController}) —
 * attestations, breach human verbs, and the run-now lever; {@code {data,
 * meta, errors}} enveloped via {@code ResponseEnvelopeAdvice}.
 *
 * <ul>
 *   <li>{@code POST /commitments/{id}/attest} — §5.5 idempotent upsert with
 *       §5.3 claim side effects (MISSED auto-opens a breach).</li>
 *   <li>{@code POST /breaches/{id}/acknowledge|remediate|waive} — the §2.4
 *       human lifecycle verbs; these SURVIVE decline (§6.3). Waive is
 *       HUMAN-only (ADR D16 → 403 for non-human actors) and hash-chain
 *       mirrored (ADR D14).</li>
 *   <li>{@code POST /contracts/{idOrKey}/monitors/{monitorId}/run-now} — the
 *       §5.2 operator/acceptance lever: synchronous single-monitor execution
 *       bypassing {@code next_run_at}, honoring locks, surfacing DETECTED →
 *       SURFACED before returning (§13 row 3b).</li>
 * </ul>
 *
 * <p>Actor identity is persona-header HUMAN, verbatim (S9 posture; spec §15
 * #12) — see {@link PersonaActors}. Agent writes arrive over the governed MCP
 * surface (3c), never here.
 */
@RestController
@RequestMapping("/api/v1")
public class ContractAdminController {

    private final AttestationService attestationService;
    private final BreachService breachService;
    /** Provider: the scheduler bean is absent when the §1 master switch is off. */
    private final ObjectProvider<ContractMonitorScheduler> scheduler;
    private final ContractReadService readService;
    private final ContractMonitorRepository monitorRepository;
    private final PersonaContext personaContext;

    public ContractAdminController(AttestationService attestationService,
                                   BreachService breachService,
                                   ObjectProvider<ContractMonitorScheduler> scheduler,
                                   ContractReadService readService,
                                   ContractMonitorRepository monitorRepository,
                                   PersonaContext personaContext) {
        this.attestationService = attestationService;
        this.breachService = breachService;
        this.scheduler = scheduler;
        this.readService = readService;
        this.monitorRepository = monitorRepository;
        this.personaContext = personaContext;
    }

    /** §7.2/§5.5 attest — 200 with the (latest-wins) attestation row. */
    @PostMapping("/commitments/{commitmentId}/attest")
    public AttestationResponse attest(@PathVariable UUID commitmentId,
                                      @Valid @RequestBody ContractAttestRequest request) {
        return AttestationResponse.from(attestationService.attest(
                new AttestationService.AttestCommand(
                        commitmentId, request.periodKey(),
                        parseEnum(ComplianceClaim.class, request.complianceClaim(), "complianceClaim"),
                        request.statement(), request.evidenceRefs(),
                        PersonaActors.identity(personaContext), "API",
                        PersonaActors.human(personaContext))));
    }

    /** §2.4 SURFACED → ACKNOWLEDGED (body optional; agents MAY acknowledge — ADR D16). */
    @PostMapping("/breaches/{breachId}/acknowledge")
    public BreachResponse acknowledge(@PathVariable UUID breachId,
                                      @RequestBody(required = false) BreachAcknowledgeRequest request) {
        return BreachResponse.from(breachService.acknowledge(breachId,
                request != null ? request.note() : null, PersonaActors.human(personaContext)));
    }

    /** §2.4 ACKNOWLEDGED → REMEDIATED — evidence mandatory; commitment back to COMPLIANT. */
    @PostMapping("/breaches/{breachId}/remediate")
    public BreachResponse remediate(@PathVariable UUID breachId,
                                    @Valid @RequestBody BreachRemediateRequest request) {
        return BreachResponse.from(breachService.remediate(breachId, request.evidenceRefs(),
                PersonaActors.human(personaContext)));
    }

    /**
     * §2.4 ACKNOWLEDGED → WAIVED — rationale mandatory; HUMAN-only (ADR D16:
     * non-human actors → 403 {@code WaiverHumanOnlyException}); ADR D14
     * hash-chain mirrored.
     */
    @PostMapping("/breaches/{breachId}/waive")
    public BreachResponse waive(@PathVariable UUID breachId,
                                @Valid @RequestBody BreachWaiveRequest request) {
        return BreachResponse.from(breachService.waive(breachId, request.rationale(),
                PersonaActors.human(personaContext)));
    }

    /**
     * §5.2/§7.2 run-now — the user-runnable acceptance lever (§13 row 3b).
     * Returns the appended run row; 404 when the monitor does not belong to
     * the resolved contract; 409 {@code INVALID_CONTRACT_STATE} for SUSPENDED
     * monitors (decline suspends monitors, §2.2).
     */
    @PostMapping("/contracts/{idOrKey}/monitors/{monitorId}/run-now")
    public MonitorRunResponse runNow(@PathVariable String idOrKey,
                                     @PathVariable UUID monitorId) {
        ContractMonitorScheduler runner = scheduler.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Monitor execution is disabled (substrate.contracts.monitor.enabled=false)");
        }
        Contract contract = readService.resolve(idOrKey)
                .orElseThrow(() -> new EntityNotFoundException("Contract", idOrKey));
        ContractMonitor monitor = monitorRepository.findById(monitorId)
                .filter(m -> contract.getId().equals(m.getContractId()))
                .orElseThrow(() -> new EntityNotFoundException("ContractMonitor", monitorId));
        return MonitorRunResponse.from(runner.runNow(monitor.getId()));
    }

    // ----------------------------------------------------------------- helpers

    /** Case-insensitive enum parse; unknown values → 400 (ContractController idiom). */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String param) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid value for parameter '" + param + "': " + raw
                    + " (expected one of " + Arrays.stream(type.getEnumConstants())
                            .map(Enum::name).collect(Collectors.joining(", ")) + ")");
        }
    }
}
