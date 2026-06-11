package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * §5.3 ATTESTATION_CADENCE — "for each commitment in
 * {@code rule_spec.commitmentIds} (default: all cadence-bearing commitments of
 * the contract): most recent closed period P; attestation exists for
 * (commitment, P)?"
 *
 * <ul>
 *   <li>claim ON_TRACK/COMPLETE → COMPLIANT (commitment → COMPLIANT)</li>
 *   <li>claim AT_RISK → COMPLIANT run outcome, commitment → AT_RISK (the
 *       "signal" is the read-model state itself — §2.3 denormalized
 *       projection; no signal table exists in v1)</li>
 *   <li>claim MISSED → BREACH_DETECTED (the attestation write usually already
 *       opened this breach as {@code detected_by=HUMAN_ATTESTATION}; the
 *       sweep's candidate refreshes it — same ADR D13 key via
 *       {@link MonitorBindings#cadenceMonitorFor})</li>
 *   <li>no attestation, now ≤ periodEnd+graceDays → NOT_DUE</li>
 *   <li>no attestation, grace exceeded → BREACH_DETECTED (commitment →
 *       BREACHED, applied via BreachService)</li>
 * </ul>
 *
 * <p><b>Later-attestation rule (§13 3b acceptance flow):</b> when period P is
 * unattested but a LATER period (typically the current open one) carries an
 * attestation, that later claim governs the run — the party has resumed
 * reporting; the missed period's already-open breach row keeps its human
 * lifecycle (§2.4 — a PASS never auto-closes it; the run service appends
 * {@code BREACH_COMPLIANCE_RESTORED}). The skipped gap is recorded in the
 * trace, never silent.
 *
 * <p>Commitments without §5.1 period-key semantics (no cadence anywhere,
 * unsupported cadence spelling) cannot be evaluated — contributed as
 * INDETERMINATE with a trace note (flag-not-invent; PeriodKeys class javadoc).
 *
 * <p>Aggregation across commitments (worst-wins, documented):
 * any breach ⇒ BREACH_DETECTED; else any evaluated claim ⇒ COMPLIANT; else
 * any in-grace gap ⇒ NOT_DUE; else INDETERMINATE (nothing evaluable).
 */
@Component
public class AttestationCadenceEvaluator implements MonitorEvaluator {

    private final ContractCommitmentRepository commitmentRepository;
    private final ContractAttestationRepository attestationRepository;
    private final ObjectMapper objectMapper;

    public AttestationCadenceEvaluator(ContractCommitmentRepository commitmentRepository,
                                       ContractAttestationRepository attestationRepository,
                                       ObjectMapper objectMapper) {
        this.commitmentRepository = commitmentRepository;
        this.attestationRepository = attestationRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public MonitorRuleType ruleType() {
        return MonitorRuleType.ATTESTATION_CADENCE;
    }

    @Override
    public MonitorEvaluation evaluate(ContractMonitor monitor, PeriodContext ctx) {
        ObjectNode trace = objectMapper.createObjectNode();
        trace.put("rule", MonitorRuleType.ATTESTATION_CADENCE.name());
        trace.put("graceDays", monitor.getGraceDays());
        ArrayNode commitmentTraces = trace.putArray("commitments");

        List<ContractCommitment> commitments = resolveCommitments(monitor, trace);
        if (commitments.isEmpty()) {
            trace.put("note", "no evaluable commitments resolved (rule_spec.commitmentIds empty/unresolvable"
                    + " and no cadence-bearing commitments on the contract)");
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, null, trace);
        }

        boolean anyBreach = false;
        boolean anyEvaluated = false;
        boolean anyNotDue = false;
        String runPeriodKey = null;
        List<MonitorEvaluation.BreachCandidate> breaches = new ArrayList<>();
        List<MonitorEvaluation.CommitmentState> states = new ArrayList<>();

        for (ContractCommitment commitment : commitments) {
            ObjectNode c = commitmentTraces.addObject();
            c.put("commitmentId", commitment.getId().toString());
            String cadence = effectiveCadence(commitment, monitor);
            c.put("cadence", cadence);
            String period = PeriodKeys.previousPeriodKey(cadence, ctx.now(), ctx.zone());
            if (period == null) {
                c.put("result", "INDETERMINATE");
                c.put("note", "cadence has no §5.1 period-key semantics — not evaluable (flag-not-invent)");
                continue;
            }
            c.put("periodKey", period);
            runPeriodKey = runPeriodKey == null ? period : runPeriodKey;

            Optional<ContractAttestation> attestation =
                    attestationRepository.findByCommitmentIdAndPeriodKey(commitment.getId(), period);
            String governingPeriod = period;
            if (attestation.isEmpty()) {
                attestation = latestAttestationAfter(commitment.getId(), period);
                if (attestation.isPresent()) {
                    governingPeriod = attestation.get().getPeriodKey();
                    c.put("laterAttestationGoverns", governingPeriod);
                    c.put("unattestedGapPeriod", period);
                }
            }

            if (attestation.isPresent()) {
                anyEvaluated = true;
                switch (attestation.get().getComplianceClaim()) {
                    case ON_TRACK, COMPLETE -> {
                        c.put("claim", attestation.get().getComplianceClaim().name());
                        c.put("result", "COMPLIANT");
                        states.add(new MonitorEvaluation.CommitmentState(
                                commitment.getId(), CommitmentComplianceState.COMPLIANT));
                    }
                    case AT_RISK -> {
                        c.put("claim", "AT_RISK");
                        c.put("result", "COMPLIANT_AT_RISK");
                        states.add(new MonitorEvaluation.CommitmentState(
                                commitment.getId(), CommitmentComplianceState.AT_RISK));
                    }
                    case MISSED -> {
                        c.put("claim", "MISSED");
                        c.put("result", "BREACH_DETECTED");
                        anyBreach = true;
                        breaches.add(new MonitorEvaluation.BreachCandidate(
                                commitment.getId(), governingPeriod,
                                "Attested MISSED for period " + governingPeriod
                                        + " — " + shortText(commitment)));
                    }
                }
                continue;
            }

            // no attestation at all — §5.1 grace arithmetic on P
            Optional<LocalDate> periodEnd = PeriodKeys.periodEnd(period);
            if (periodEnd.isEmpty()) {
                c.put("result", "INDETERMINATE");
                c.put("note", "period end unresolvable");
                continue;
            }
            LocalDate graceDeadline = periodEnd.get().plusDays(monitor.getGraceDays());
            LocalDate today = ctx.now().atZone(ctx.zone()).toLocalDate();
            c.put("graceDeadline", graceDeadline.toString());
            if (!today.isAfter(graceDeadline)) {
                c.put("result", "NOT_DUE");
                anyNotDue = true;
            } else {
                c.put("result", "BREACH_DETECTED");
                anyBreach = true;
                breaches.add(new MonitorEvaluation.BreachCandidate(
                        commitment.getId(), period,
                        "Missing attestation for period " + period + " past "
                                + monitor.getGraceDays() + " grace days — " + shortText(commitment)));
            }
        }

        MonitorRunOutcome outcome = anyBreach ? MonitorRunOutcome.BREACH_DETECTED
                : anyEvaluated ? MonitorRunOutcome.COMPLIANT
                : anyNotDue ? MonitorRunOutcome.NOT_DUE
                : MonitorRunOutcome.INDETERMINATE;
        return new MonitorEvaluation(outcome, runPeriodKey, trace,
                anyBreach ? breaches : List.of(), states);
    }

    // ------------------------------------------------------------------ helpers

    private List<ContractCommitment> resolveCommitments(ContractMonitor monitor, ObjectNode trace) {
        List<UUID> bound = MonitorBindings.boundCommitmentIds(monitor);
        if (!bound.isEmpty()) {
            List<ContractCommitment> resolved = new ArrayList<>();
            for (UUID id : bound) {
                commitmentRepository.findById(id)
                        .filter(c -> monitor.getContractId().equals(c.getContractId()))
                        .ifPresentOrElse(resolved::add,
                                () -> trace.withArray("unresolvedCommitmentIds").add(id.toString()));
            }
            return resolved;
        }
        return commitmentRepository.findByContractIdOrderBySequenceOrderAsc(monitor.getContractId())
                .stream()
                .filter(c -> c.getCadence() != null && !c.getCadence().isBlank())
                .toList();
    }

    /** Commitment cadence first (table-4 column); monitor cadence as fallback. */
    private static String effectiveCadence(ContractCommitment commitment, ContractMonitor monitor) {
        return commitment.getCadence() != null && !commitment.getCadence().isBlank()
                ? commitment.getCadence() : monitor.getCadence();
    }

    /** Latest attestation for a period ending strictly after {@code period}'s end. */
    private Optional<ContractAttestation> latestAttestationAfter(UUID commitmentId, String period) {
        Optional<LocalDate> baseline = PeriodKeys.periodEnd(period);
        if (baseline.isEmpty()) {
            return Optional.empty();
        }
        return attestationRepository.findByCommitmentId(commitmentId).stream()
                .filter(a -> PeriodKeys.periodEnd(a.getPeriodKey())
                        .map(end -> end.isAfter(baseline.get())).orElse(false))
                .max(Comparator.comparing(a -> PeriodKeys.periodEnd(a.getPeriodKey()).orElseThrow()));
    }

    private static String shortText(ContractCommitment commitment) {
        String text = commitment.getText() == null ? "" : commitment.getText();
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }
}
