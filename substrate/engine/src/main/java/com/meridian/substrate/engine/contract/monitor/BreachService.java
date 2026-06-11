package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachResolution;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractAccountabilityMirror;
import com.meridian.substrate.engine.contract.events.AfterCommit;
import com.meridian.substrate.engine.contract.events.BreachSurfacedEvent;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.exception.WaiverHumanOnlyException;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import com.meridian.substrate.engine.observability.EngineMetrics;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Breach open/refresh + surfacing + human verbs (engine-spec contracts.md
 * §5.4, §2.4; ADR D13 dedup, ADR D16 waiver guard, ADR D14 waiver mirror).
 *
 * <p><b>Dedup invariant (ADR D13):</b> at most one OPEN breach per
 * {@code (monitor_id, commitment_id, period_key)} — {@link #onBreachDetected}
 * takes the {@code findOpenBySourceForUpdate} row lock and refreshes
 * {@code last_confirmed_at} ({@code BREACH_RECONFIRMED}) instead of opening a
 * duplicate. Backstop: the V5_1 Postgres partial unique index. A true
 * concurrent first-insert race (no committed row for either transaction to
 * see/lock) is resolved by that index — the losing transaction rolls back and
 * its caller retries in a fresh transaction, which then sees the committed
 * row and refreshes (§10.6): {@code AttestationService} retries its upsert
 * once; sweep losses are re-detected by the next sweep (per-monitor
 * SKIP-LOCKED claims make same-key monitor races impossible in the first
 * place).
 *
 * <p><b>Lifecycle (§2.4):</b> monotonic forward-only, rows never deleted,
 * every transition appends {@code contract_events} with actor identity.
 * Breach ≠ decline structurally; open breaches survive decline and keep these
 * verbs available (§6.3) — hence NO contract-status guard here.
 */
@Service
public class BreachService {

    private static final Logger log = LoggerFactory.getLogger(BreachService.class);

    /** Engine-internal actor for the sweep's surfacing step (§2.1 SYSTEM). */
    public static final String SWEEP_ACTOR_ID = "contract-monitor-sweep";

    /** One would-be open/refresh (§5.4 {@code onBreachDetected} signature + provenance). */
    public record OpenBreachCommand(UUID contractId, UUID monitorId, UUID commitmentId,
                                    String periodKey, Severity severity, String summary,
                                    BreachDetectedBy detectedBy, ContractActor actor) {
    }

    private final ContractBreachRepository breachRepository;
    private final ContractCommitmentRepository commitmentRepository;
    private final ContractRepository contractRepository;
    private final ContractEventService eventService;
    private final ContractAccountabilityMirror mirror;
    private final EvidenceRefValidator evidenceRefValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final EngineMetrics metrics;

    /** Spring constructor (Stage 3 observability — metrics optional so partial slices wire). */
    @Autowired
    public BreachService(ContractBreachRepository breachRepository,
                         ContractCommitmentRepository commitmentRepository,
                         ContractRepository contractRepository,
                         ContractEventService eventService,
                         ContractAccountabilityMirror mirror,
                         EvidenceRefValidator evidenceRefValidator,
                         ApplicationEventPublisher eventPublisher,
                         Clock clock,
                         ObjectMapper objectMapper,
                         ObjectProvider<EngineMetrics> metrics) {
        this(breachRepository, commitmentRepository, contractRepository, eventService, mirror,
                evidenceRefValidator, eventPublisher, clock, objectMapper,
                metrics.getIfAvailable(EngineMetrics::noop));
    }

    public BreachService(ContractBreachRepository breachRepository,
                         ContractCommitmentRepository commitmentRepository,
                         ContractRepository contractRepository,
                         ContractEventService eventService,
                         ContractAccountabilityMirror mirror,
                         EvidenceRefValidator evidenceRefValidator,
                         ApplicationEventPublisher eventPublisher,
                         Clock clock,
                         ObjectMapper objectMapper,
                         EngineMetrics metrics) {
        this.breachRepository = breachRepository;
        this.commitmentRepository = commitmentRepository;
        this.contractRepository = contractRepository;
        this.eventService = eventService;
        this.mirror = mirror;
        this.evidenceRefValidator = evidenceRefValidator;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Pre-Stage-3 signature, kept for hand-constructed services (tests): metrics → sink. */
    public BreachService(ContractBreachRepository breachRepository,
                         ContractCommitmentRepository commitmentRepository,
                         ContractRepository contractRepository,
                         ContractEventService eventService,
                         ContractAccountabilityMirror mirror,
                         EvidenceRefValidator evidenceRefValidator,
                         ApplicationEventPublisher eventPublisher,
                         Clock clock,
                         ObjectMapper objectMapper) {
        this(breachRepository, commitmentRepository, contractRepository, eventService, mirror,
                evidenceRefValidator, eventPublisher, clock, objectMapper, EngineMetrics.noop());
    }

    // ------------------------------------------------------------ open/refresh

    /**
     * §5.4 open-or-refresh under the dedup row lock. MUST be called inside an
     * active transaction (the per-monitor claim transaction, or the
     * attestation upsert transaction) — the lock and the state change must
     * commit together.
     *
     * @return the open breach row (new DETECTED or refreshed existing)
     */
    public ContractBreach onBreachDetected(OpenBreachCommand cmd) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "onBreachDetected requires an active transaction (§5.4 row-lock dedup)");
        }
        Instant now = clock.instant();
        var existing = breachRepository.findOpenBySourceForUpdate(
                cmd.contractId(), cmd.monitorId(), cmd.commitmentId(), cmd.periodKey());
        if (existing.isPresent()) {
            ContractBreach breach = existing.get();
            breach.setLastConfirmedAt(now);
            breachRepository.save(breach);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("periodKey", cmd.periodKey());
            payload.put("detectedBy", cmd.detectedBy().name());
            eventService.append(cmd.contractId(), ContractEventService.BREACH_RECONFIRMED,
                    breach.getId(), cmd.actor(), payload);
            log.info("Breach RECONFIRMED [id={}, contractId={}, monitorId={}, period={}]",
                    breach.getId(), cmd.contractId(), cmd.monitorId(), cmd.periodKey());
            return breach;
        }

        ContractBreach breach = new ContractBreach();
        breach.setContractId(cmd.contractId());
        breach.setMonitorId(cmd.monitorId());
        breach.setCommitmentId(cmd.commitmentId());
        breach.setPeriodKey(cmd.periodKey());
        breach.setStatus(BreachStatus.DETECTED);
        breach.setSeverity(cmd.severity());
        breach.setSummary(cmd.summary());
        breach.setDetectedAt(now);
        breach.setDetectedBy(cmd.detectedBy());
        breach.setLastConfirmedAt(now);
        breach = breachRepository.save(breach);
        metrics.breachOpened(cmd.detectedBy() == null ? null : cmd.detectedBy().name());

        if (cmd.commitmentId() != null) {
            enterBreached(cmd.commitmentId(), now);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("periodKey", cmd.periodKey());
        payload.put("summary", cmd.summary());
        payload.put("detectedBy", cmd.detectedBy().name());
        if (cmd.severity() != null) {
            payload.put("severity", cmd.severity().name());
        }
        eventService.append(cmd.contractId(), ContractEventService.BREACH_DETECTED,
                breach.getId(), cmd.actor(), payload);
        log.info("Breach DETECTED [id={}, contractId={}, monitorId={}, commitmentId={}, period={}]",
                breach.getId(), cmd.contractId(), cmd.monitorId(), cmd.commitmentId(), cmd.periodKey());
        return breach;
    }

    // --------------------------------------------------------------- surfacing

    /**
     * §5.4 surface step — same sweep, SEPARATE transaction: every DETECTED
     * breach moves to SURFACED ({@code surfaced_at} set), appends
     * {@code BREACH_SURFACED}, and publishes {@link BreachSurfacedEvent} after
     * commit. Idempotent (SURFACED rows leave the candidate set). v1 is
     * synchronous; dedupe/batching is why SURFACED is a distinct status
     * (§2.4).
     */
    @Ungoverned(reason = "engine-internal sweep surfacing step (§5.4, SYSTEM actor) — not one of"
            + " the three ADR D2 PEPs; only the human verbs below are governed commands")
    @Transactional
    public int surfaceAllDetected() {
        Instant now = clock.instant();
        ContractActor actor = ContractActor.system(SWEEP_ACTOR_ID);
        int surfaced = 0;
        for (ContractBreach breach : breachRepository.findByStatus(BreachStatus.DETECTED)) {
            transition(breach, BreachStatus.SURFACED);
            breach.setSurfacedAt(now);
            breachRepository.save(breach);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("summary", breach.getSummary());
            eventService.append(breach.getContractId(), ContractEventService.BREACH_SURFACED,
                    breach.getId(), actor, payload);
            Contract contract = contractRepository.findById(breach.getContractId()).orElse(null);
            AfterCommit.publish(eventPublisher, new BreachSurfacedEvent(
                    breach.getId(), breach.getContractId(),
                    contract != null ? contract.getDomainId() : null,
                    contract != null ? contract.getContractKey() : null,
                    breach.getSeverity(), breach.getSummary()));
            surfaced++;
        }
        if (surfaced > 0) {
            log.info("Surfaced {} DETECTED breach(es)", surfaced);
        }
        return surfaced;
    }

    // -------------------------------------------------------------- human verbs

    /** §2.4 SURFACED → ACKNOWLEDGED; actor recorded verbatim (agents MAY acknowledge — ADR D16). */
    @Governed(actionType = GovernedActions.CONTRACT_ACKNOWLEDGE_BREACH)
    @Transactional
    public ContractBreach acknowledge(UUID breachId, String note, ContractActor actor) {
        ContractBreach breach = loadBreach(breachId);
        transition(breach, BreachStatus.ACKNOWLEDGED);
        breach.setAcknowledgedBy(actor.id());
        breach.setAcknowledgedAt(clock.instant());
        breachRepository.save(breach);
        ObjectNode payload = objectMapper.createObjectNode();
        if (note != null && !note.isBlank()) {
            payload.put("note", note);
        }
        eventService.append(breach.getContractId(), ContractEventService.BREACH_ACKNOWLEDGED,
                breach.getId(), actor, payload);
        return breach;
    }

    /**
     * §2.4 ACKNOWLEDGED → REMEDIATED: {@code remediation_evidence_refs}
     * mandatory (service-enforced; soft Evidence/DocumentRegistry refs,
     * warn-don't-fail on existence). Sets the commitment back to COMPLIANT.
     */
    @Governed(actionType = GovernedActions.CONTRACT_REMEDIATE_BREACH)
    @Transactional
    public ContractBreach remediate(UUID breachId, JsonNode evidenceRefs, ContractActor actor) {
        if (evidenceRefs == null || !evidenceRefs.isArray() || evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "remediate requires a non-empty evidenceRefs array (§2.4 service-enforced)");
        }
        ContractBreach breach = loadBreach(breachId);
        transition(breach, BreachStatus.REMEDIATED);
        evidenceRefValidator.warnIfMissing(evidenceRefs, "breach " + breachId + " remediation");
        breach.setResolution(BreachResolution.REMEDIATED);
        breach.setResolutionActor(actor.id());
        breach.setRemediationEvidenceRefs(evidenceRefs);
        breachRepository.save(breach);

        if (breach.getCommitmentId() != null) {
            applyResolutionState(breach.getCommitmentId(), CommitmentComplianceState.COMPLIANT);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("evidenceRefs", evidenceRefs);
        eventService.append(breach.getContractId(), ContractEventService.BREACH_REMEDIATED,
                breach.getId(), actor, payload);
        return breach;
    }

    /**
     * §2.4 ACKNOWLEDGED → WAIVED: human actor + rationale mandatory;
     * <b>agents may never waive</b> (ADR D16 built-in non-removable guard).
     * Two layers, deliberately redundant: the {@code @Governed} PEP presents
     * the waiver as {@link GovernedActions#CONTRACT_WAIVE_BREACH} where the
     * gateway's hardcoded D16 check denies AGENT principals (enforce blocks,
     * shadow rows WOULD_DENY); the actor check below is the mode-independent
     * service backstop ({@link WaiverHumanOnlyException}) so the invariant
     * holds even in shadow/off. Mirrored to the hash chain (ADR D14). Sets
     * the commitment to WAIVED.
     */
    @Governed(actionType = GovernedActions.CONTRACT_WAIVE_BREACH)
    @Transactional
    public ContractBreach waive(UUID breachId, String rationale, ContractActor actor) {
        if (actor.type() != ContractActorType.HUMAN) {
            throw new WaiverHumanOnlyException(breachId, actor.type());
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException(
                    "waive requires a non-blank rationale (§2.4 service-enforced)");
        }
        ContractBreach breach = loadBreach(breachId);
        transition(breach, BreachStatus.WAIVED);
        breach.setResolution(BreachResolution.WAIVED);
        breach.setResolutionActor(actor.id());
        breach.setResolutionRationale(rationale);
        breachRepository.save(breach);

        if (breach.getCommitmentId() != null) {
            applyResolutionState(breach.getCommitmentId(), CommitmentComplianceState.WAIVED);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("rationale", rationale);
        eventService.append(breach.getContractId(), ContractEventService.BREACH_WAIVED,
                breach.getId(), actor, payload);
        contractRepository.findById(breach.getContractId())
                .ifPresent(contract -> mirror.onBreachWaived(contract, breach));
        return breach;
    }

    // ------------------------------------------------------------------ helpers

    private ContractBreach loadBreach(UUID breachId) {
        return breachRepository.findById(breachId)
                .orElseThrow(() -> new EntityNotFoundException("ContractBreach not found: " + breachId));
    }

    /**
     * §2.4 monotonic forward-only guard, table-as-data
     * ({@link BreachStatus#canTransitionTo}). An out-of-order verb is a
     * state conflict (the §4.5 409 family), never a silent skip.
     */
    private void transition(ContractBreach breach, BreachStatus target) {
        if (!breach.getStatus().canTransitionTo(target)) {
            throw new ContractConflictException(
                    ContractConflictException.Code.INVALID_CONTRACT_STATE, breach.getContractId(),
                    "illegal breach transition " + breach.getStatus() + " -> " + target
                            + " (§2.4 monotonic; breach " + breach.getId() + ")");
        }
        breach.setStatus(target);
    }

    /** §2.3 breach-entry edge: any pre-breach state may enter BREACHED when a breach opens. */
    private void enterBreached(UUID commitmentId, Instant now) {
        ContractCommitment commitment = commitmentRepository.findById(commitmentId).orElse(null);
        if (commitment == null) {
            log.warn("Breach references unknown commitment {} — soft-ref posture, recorded anyway",
                    commitmentId);
            return;
        }
        if (commitment.getComplianceState() != CommitmentComplianceState.BREACHED
                && commitment.getComplianceState().canTransitionTo(CommitmentComplianceState.BREACHED)) {
            commitment.setComplianceState(CommitmentComplianceState.BREACHED);
        }
        commitment.setLastEvaluatedAt(now);
        commitmentRepository.save(commitment);
    }

    /** §2.4 resolution edges: BREACHED → COMPLIANT (remediated) / WAIVED (waived). */
    private void applyResolutionState(UUID commitmentId, CommitmentComplianceState target) {
        commitmentRepository.findById(commitmentId).ifPresent(commitment -> {
            if (commitment.getComplianceState().canTransitionTo(target)) {
                commitment.setComplianceState(target);
                commitment.setLastEvaluatedAt(clock.instant());
                commitmentRepository.save(commitment);
            } else {
                // e.g. a second open breach already re-entered BREACHED; the
                // projection follows the breach set, never forced (§2.3).
                log.info("Commitment {} stays {} (resolution target {} not applicable)",
                        commitmentId, commitment.getComplianceState(), target);
            }
        });
    }
}
