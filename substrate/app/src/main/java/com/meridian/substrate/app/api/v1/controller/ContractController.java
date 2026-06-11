package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.ContractDeclineRequest;
import com.meridian.substrate.app.api.v1.request.ContractMetricSampleRequest;
import com.meridian.substrate.app.api.v1.request.ContractRespondRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.engine.contract.monitor.MetricSampleService;
import com.meridian.substrate.engine.contract.read.AttestationResponse;
import com.meridian.substrate.engine.contract.read.BreachResponse;
import com.meridian.substrate.engine.contract.read.ContractCardResponse;
import com.meridian.substrate.engine.contract.read.ContractDetailResponse;
import com.meridian.substrate.engine.contract.read.ContractEventResponse;
import com.meridian.substrate.engine.contract.read.EnforcementDecisionResponse;
import com.meridian.substrate.engine.contract.read.MetricSampleResponse;
import com.meridian.substrate.engine.contract.read.MonitorRunResponse;
import com.meridian.substrate.engine.contract.read.SignalsResponse;
import com.meridian.substrate.engine.contract.registry.ContractReadService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import com.meridian.substrate.engine.contract.registry.ContractSignalService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Contract READ endpoints (engine-spec contracts.md §7.1, chunk 3a) + the
 * §7.2 respond / decline / metric-sample WRITE endpoints (chunk 3b-2) —
 * {@code {data, meta, errors}} enveloped via {@code ResponseEnvelopeAdvice}.
 * The read model shipped FIRST per the binding read-model-first build order
 * (ADR D18). The write endpoints are operator/curl/MCP-facing only: the UI
 * Review/Decline affordances stay INERT (queue #28 — Mike's product-design
 * session gates the UX; spec §15 #3).
 *
 * <p><b>DECLINE is never an error</b> (port-map trap #8; ADR-002 D6): both
 * decline lanes return <b>200</b> with the full detail. 409s arise only from
 * frozen content, conflicting responses, or wrong-state commands.
 *
 * <p>{@code {idOrKey}} resolves UUID first, then {@code contract_key} (slug
 * identity, ADR D8 — fixture deep links and {@code monitorMechanism} prose
 * bind to the key). All sub-resource paths accept either currency.
 *
 * <p>Actor identity: persona headers via {@link PersonaContext} —
 * HUMAN-typed, recorded verbatim, spoofable pre-Phase-5 (port-map S9; spec
 * §15 #12). Agent principals arrive over the MCP surface (3c), never here.
 *
 * <p>Optional enum filters ({@code status}, {@code decision}) parse
 * case-insensitively; an unknown value is a plain 400 via the
 * {@code IllegalArgumentException} mapping — DECLINED here is filter input,
 * never an error semantic (port-map trap #8).
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractReadService readService;
    private final ContractSignalService signalService;
    private final ContractRegistryService registryService;
    private final MetricSampleService metricSampleService;
    private final PersonaContext personaContext;

    public ContractController(ContractReadService readService,
                              ContractSignalService signalService,
                              ContractRegistryService registryService,
                              MetricSampleService metricSampleService,
                              PersonaContext personaContext) {
        this.readService = readService;
        this.signalService = signalService;
        this.registryService = registryService;
        this.metricSampleService = metricSampleService;
        this.personaContext = personaContext;
    }

    /** §7.1 list — {@code domainId} required; {@code status}/{@code type} optional. */
    @GetMapping
    public List<ContractCardResponse> list(@RequestParam UUID domainId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String type) {
        return readService.cards(domainId, parseEnum(ContractStatus.class, status, "status"), type);
    }

    /**
     * §11.2 signals — engine-computed at read time (severs fixture stat seam
     * S6). Declared on the literal path so it wins over {@code {idOrKey}}.
     */
    @GetMapping("/signals")
    public SignalsResponse signals(@RequestParam UUID domainId) {
        return signalService.signals(domainId);
    }

    /** §7.3 detail — the BeaconContract superset; 404 unknown id/key. */
    @GetMapping("/{idOrKey}")
    public ContractDetailResponse detail(@PathVariable String idOrKey) {
        return readService.detail(resolve(idOrKey));
    }

    /** §7.1 events — append-only audit timeline ascending; {@code after} optional ISO ts. */
    @GetMapping("/{idOrKey}/events")
    public List<ContractEventResponse> events(
            @PathVariable String idOrKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant after) {
        return readService.events(resolve(idOrKey).getId(), after);
    }

    /** §7.1 breaches — {@code status} optional ({@code DETECTED}…{@code WAIVED}). */
    @GetMapping("/{idOrKey}/breaches")
    public List<BreachResponse> breaches(@PathVariable String idOrKey,
                                         @RequestParam(required = false) String status) {
        return readService.breaches(resolve(idOrKey).getId(),
                parseEnum(BreachStatus.class, status, "status"));
    }

    /** §7.1 decisions — PDP log lane (rows from the 3c gateway); {@code since}/{@code decision} optional. */
    @GetMapping("/{idOrKey}/decisions")
    public List<EnforcementDecisionResponse> decisions(
            @PathVariable String idOrKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant since,
            @RequestParam(required = false) String decision) {
        return readService.decisions(resolve(idOrKey), since,
                parseEnum(GatewayDecision.class, decision, "decision"));
    }

    /** §7.1 monitor-runs — {@code monitorId}/{@code since} optional. */
    @GetMapping("/{idOrKey}/monitor-runs")
    public List<MonitorRunResponse> monitorRuns(
            @PathVariable String idOrKey,
            @RequestParam(required = false) UUID monitorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant since) {
        return readService.monitorRuns(resolve(idOrKey).getId(), monitorId, since);
    }

    /** §7.1 attestations — {@code commitmentId} optional narrowing. */
    @GetMapping("/{idOrKey}/attestations")
    public List<AttestationResponse> attestations(@PathVariable String idOrKey,
                                                  @RequestParam(required = false) UUID commitmentId) {
        return readService.attestations(resolve(idOrKey).getId(), commitmentId);
    }

    // ----------------------------------------------------- §7.2 write commands

    /**
     * §7.2 respond — records the immutable party response; may transition per
     * §2.2 (LAST required acceptance activates; ANY required decline resolves
     * DECLINED — returning <b>200</b> with the detail, a legitimate outcome).
     * Duplicate identical responses are idempotent 200s; conflicting → 409
     * {@code RESPONSE_CONFLICT}.
     */
    @PostMapping("/{idOrKey}/respond")
    public ContractDetailResponse respond(@PathVariable String idOrKey,
                                          @Valid @RequestBody ContractRespondRequest request) {
        Contract contract = resolve(idOrKey);
        registryService.respond(contract.getId(), new ContractRegistryService.RespondCommand(
                request.partyId(),
                parseEnum(PartyResponse.class, request.response(), "response"),
                request.declineTriggerId(), request.declineReasoning(),
                PersonaActors.identity(personaContext), PersonaActors.human(personaContext)));
        return readService.detail(resolve(idOrKey));
    }

    /**
     * §6.1/§7.2 invoke_decline — ACTIVE → DECLINED citing a pre-agreed
     * trigger, reasoning mandatory. <b>200 always on the success path</b>
     * (DECLINE is a first-class legitimate terminal resolution, never
     * error-coded); 409 only for {@code INVALID_CONTRACT_STATE}. The §6
     * advisory lanes ({@code REVIEW_BOUNDARY_UNCONFIGURED} on federation
     * contracts with a null boundary, {@code OPEN_BREACHES_AT_DECLINE}) are
     * rowed as ALLOW_WITH_FLAG decisions — visible on
     * {@code GET /{idOrKey}/decisions}.
     */
    @PostMapping("/{idOrKey}/decline")
    public ContractDetailResponse decline(@PathVariable String idOrKey,
                                          @Valid @RequestBody ContractDeclineRequest request) {
        Contract contract = resolve(idOrKey);
        registryService.invokeDecline(contract.getId(),
                new ContractRegistryService.InvokeDeclineCommand(
                        request.declineTriggerId(), request.reasoning(), request.partyId(),
                        PersonaActors.human(personaContext)));
        return readService.detail(resolve(idOrKey));
    }

    /**
     * §5.5/§7.2 metric samples — ADR D11 push-based v1 feed; idempotent
     * 4-part-unique upsert + {@code METRIC_REPORTED} event. Reporter identity
     * recorded verbatim (advisory pre-Phase-5).
     */
    @PostMapping("/{idOrKey}/metric-samples")
    public MetricSampleResponse reportMetric(@PathVariable String idOrKey,
                                             @Valid @RequestBody ContractMetricSampleRequest request) {
        Contract contract = resolve(idOrKey);
        return MetricSampleResponse.from(metricSampleService.report(
                new MetricSampleService.ReportMetricCommand(
                        contract.getId(), request.partyId(), request.metricKey(),
                        request.value(), request.periodKey(),
                        PersonaActors.identity(personaContext),
                        PersonaActors.human(personaContext))));
    }

    // ----------------------------------------------------------------- helpers

    private Contract resolve(String idOrKey) {
        return readService.resolve(idOrKey)
                .orElseThrow(() -> new EntityNotFoundException("Contract", idOrKey));
    }

    /**
     * Case-insensitive optional enum filter; unknown values become a 400 via
     * the {@code IllegalArgumentException} mapping in
     * {@code GlobalExceptionHandler} (CascadeController precedent).
     */
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
