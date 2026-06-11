package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractEvent;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * §5.5 attestations: idempotent upsert on {@code UNIQUE(commitment_id,
 * period_key)} (ADR D13) — the row carries the LATEST submission; EVERY
 * submission appends a {@code COMMITMENT_ATTESTED} event with the full
 * payload, so history is durable. Claim side effects per §5.3:
 * ON_TRACK/COMPLETE ⇒ commitment COMPLIANT for the period; AT_RISK ⇒ AT_RISK
 * without a breach; MISSED ⇒ auto-opens a breach via {@link BreachService}
 * ({@code detected_by=HUMAN_ATTESTATION}), attributed to the commitment's
 * bound cadence monitor so the sweep's later re-detection REFRESHES the same
 * ADR D13 dedup key (see {@link MonitorBindings}).
 *
 * <p>Upsert concurrency (§10.5): the write runs in its own transaction; a
 * lost unique-key or {@code @Version} race is retried ONCE in a fresh
 * transaction, where the re-read finds the winner's row and updates it
 * (each attempt still appends its own event — the §14.4 race row pins
 * "one row, two events").
 *
 * <p>Status guard: commitments are "evaluable only while the contract is
 * ACTIVE" (§2.3) and attesting drives exactly that evaluation — non-ACTIVE
 * contracts reject with 409 {@code INVALID_CONTRACT_STATE} (breach human
 * verbs, which DO survive decline per §6.3, live on {@link BreachService}).
 *
 * <p>Party authority is advisory pre-Phase-5 (spec §15 #12): the caller's
 * identity is recorded verbatim.
 */
@Service
public class AttestationService {

    private static final Logger log = LoggerFactory.getLogger(AttestationService.class);

    public record AttestCommand(UUID commitmentId, String periodKey, ComplianceClaim claim,
                                String statement, JsonNode evidenceRefs, String attestedBy,
                                String submittedVia, ContractActor actor) {
    }

    private final ContractCommitmentRepository commitmentRepository;
    private final ContractRepository contractRepository;
    private final ContractAttestationRepository attestationRepository;
    private final ContractMonitorRepository monitorRepository;
    private final BreachService breachService;
    private final ContractEventService eventService;
    private final EvidenceRefValidator evidenceRefValidator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AttestationService(ContractCommitmentRepository commitmentRepository,
                              ContractRepository contractRepository,
                              ContractAttestationRepository attestationRepository,
                              ContractMonitorRepository monitorRepository,
                              BreachService breachService,
                              ContractEventService eventService,
                              EvidenceRefValidator evidenceRefValidator,
                              PlatformTransactionManager transactionManager,
                              Clock clock,
                              ObjectMapper objectMapper) {
        this.commitmentRepository = commitmentRepository;
        this.contractRepository = contractRepository;
        this.attestationRepository = attestationRepository;
        this.monitorRepository = monitorRepository;
        this.breachService = breachService;
        this.eventService = eventService;
        this.evidenceRefValidator = evidenceRefValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * §5.5 upsert + claim side effects; §10.5 one-retry concurrency posture.
     * Governed (§4.2 PEP 2 / §8.3 {@code attest_commitment}): the aspect
     * evaluates BEFORE the self-managed transactions below begin — an
     * enforce-mode DENY throws before anything mutates.
     */
    @Governed(actionType = GovernedActions.CONTRACT_ATTEST_COMMITMENT)
    public ContractAttestation attest(AttestCommand cmd) {
        validate(cmd);
        try {
            return transactionTemplate.execute(status -> doAttest(cmd));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException lostRace) {
            log.info("Attestation upsert lost a race [commitmentId={}, periodKey={}] — retrying once (§10.5)",
                    cmd.commitmentId(), cmd.periodKey(), lostRace);
            return transactionTemplate.execute(status -> doAttest(cmd));
        }
    }

    /**
     * §8.3 {@code record_commitment_progress} — "appends a
     * {@code COMMITMENT_PROGRESS} event only": no compliance projection, no
     * breach interaction, no status guard (the spec names none —
     * flag-not-invent; the event is inert narrative the timeline carries).
     * Evidence refs keep the soft-reference warn-don't-fail posture (§5.5).
     */
    @Governed(actionType = GovernedActions.CONTRACT_RECORD_COMMITMENT_PROGRESS)
    @Transactional
    public ContractEvent recordProgress(UUID commitmentId, String note, JsonNode evidenceRefs,
                                        ContractActor actor) {
        if (commitmentId == null) {
            throw new IllegalArgumentException("record_commitment_progress requires commitmentId");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("record_commitment_progress requires a non-blank note");
        }
        ContractCommitment commitment = commitmentRepository.findById(commitmentId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "ContractCommitment not found: " + commitmentId));
        evidenceRefValidator.warnIfMissing(evidenceRefs,
                "progress note for commitment " + commitment.getId());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("commitmentId", commitment.getId().toString());
        payload.put("note", note);
        if (evidenceRefs != null && !evidenceRefs.isNull()) {
            payload.set("evidenceRefs", evidenceRefs);
        }
        return eventService.append(commitment.getContractId(),
                ContractEventService.COMMITMENT_PROGRESS, commitment.getId(), actor, payload);
    }

    // ------------------------------------------------------------------ internals

    private void validate(AttestCommand cmd) {
        if (cmd.commitmentId() == null) {
            throw new IllegalArgumentException("attest requires commitmentId");
        }
        if (cmd.periodKey() == null || cmd.periodKey().isBlank()) {
            throw new IllegalArgumentException("attest requires periodKey (§5.1 key or ONE_TIME)");
        }
        if (cmd.claim() == null) {
            throw new IllegalArgumentException("attest requires complianceClaim");
        }
        if (cmd.attestedBy() == null || cmd.attestedBy().isBlank()) {
            throw new IllegalArgumentException("attest requires attestedBy");
        }
    }

    private ContractAttestation doAttest(AttestCommand cmd) {
        ContractCommitment commitment = commitmentRepository.findById(cmd.commitmentId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "ContractCommitment not found: " + cmd.commitmentId()));
        Contract contract = contractRepository.findById(commitment.getContractId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Contract not found: " + commitment.getContractId()));
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            throw new ContractConflictException(
                    ContractConflictException.Code.INVALID_CONTRACT_STATE, contract.getId(),
                    "commitments are evaluable only while the contract is ACTIVE (§2.3; was "
                            + contract.getStatus() + ")");
        }
        evidenceRefValidator.warnIfMissing(cmd.evidenceRefs(),
                "attestation for commitment " + commitment.getId());

        Instant now = clock.instant();
        ContractAttestation attestation = attestationRepository
                .findByCommitmentIdAndPeriodKey(commitment.getId(), cmd.periodKey())
                .orElseGet(ContractAttestation::new);
        attestation.setCommitmentId(commitment.getId());
        attestation.setContractId(contract.getId());
        attestation.setPartyId(commitment.getPartyId());
        attestation.setPeriodKey(cmd.periodKey());
        attestation.setAttestedBy(cmd.attestedBy());
        attestation.setAttestedAt(now);
        attestation.setComplianceClaim(cmd.claim());
        attestation.setStatement(cmd.statement());
        attestation.setEvidenceRefs(cmd.evidenceRefs());
        attestation.setSubmittedVia(cmd.submittedVia() == null || cmd.submittedVia().isBlank()
                ? "API" : cmd.submittedVia());
        attestation = attestationRepository.save(attestation);

        // §5.5: EVERY submission appends the full payload — history is durable
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("commitmentId", commitment.getId().toString());
        payload.put("periodKey", cmd.periodKey());
        payload.put("complianceClaim", cmd.claim().name());
        payload.put("attestedBy", cmd.attestedBy());
        payload.put("submittedVia", attestation.getSubmittedVia());
        if (cmd.statement() != null && !cmd.statement().isBlank()) {
            payload.put("statement", cmd.statement());
        }
        if (cmd.evidenceRefs() != null && !cmd.evidenceRefs().isNull()) {
            payload.set("evidenceRefs", cmd.evidenceRefs());
        }
        eventService.append(contract.getId(), ContractEventService.COMMITMENT_ATTESTED,
                commitment.getId(), cmd.actor(), payload);

        applyClaimSideEffects(contract, commitment, cmd, now);
        return attestation;
    }

    /** §5.3 claim mapping (§2.3 projections; MISSED auto-opens via BreachService). */
    private void applyClaimSideEffects(Contract contract, ContractCommitment commitment,
                                       AttestCommand cmd, Instant now) {
        switch (cmd.claim()) {
            case ON_TRACK, COMPLETE -> project(commitment, CommitmentComplianceState.COMPLIANT, now);
            case AT_RISK -> project(commitment, CommitmentComplianceState.AT_RISK, now);
            case MISSED -> {
                ContractMonitor boundMonitor = MonitorBindings.cadenceMonitorFor(
                                monitorRepository.findByContractId(contract.getId()), commitment)
                        .orElse(null);
                breachService.onBreachDetected(new BreachService.OpenBreachCommand(
                        contract.getId(),
                        boundMonitor != null ? boundMonitor.getId() : null,
                        commitment.getId(),
                        cmd.periodKey(),
                        boundMonitor != null ? boundMonitor.getSeverity() : null,
                        "Attested MISSED for period " + cmd.periodKey(),
                        BreachDetectedBy.HUMAN_ATTESTATION,
                        cmd.actor()));
            }
        }
    }

    /**
     * §2.3 guard (same as the monitor-driven path): BREACHED/WAIVED are owned
     * by the breach lifecycle; claims never move them directly.
     */
    private void project(ContractCommitment commitment, CommitmentComplianceState target, Instant now) {
        CommitmentComplianceState current = commitment.getComplianceState();
        if (current != target
                && current != CommitmentComplianceState.BREACHED
                && current != CommitmentComplianceState.WAIVED
                && current.canTransitionTo(target)) {
            commitment.setComplianceState(target);
        }
        commitment.setLastEvaluatedAt(now);
        commitmentRepository.save(commitment);
    }
}
