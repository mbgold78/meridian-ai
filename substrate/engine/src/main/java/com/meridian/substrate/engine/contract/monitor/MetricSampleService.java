package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * §5.5 push-based metric samples (ADR D11): idempotent upsert on
 * {@code UNIQUE(contract_id, party_id, metric_key, period_key)} +
 * {@code METRIC_REPORTED} event. Feeds the METRIC_THRESHOLD evaluator and the
 * §4.4 metric-leaf/arming resolution. Party authority is advisory pre-Phase-5
 * ({@code mcp_principal_id} mapping; spoofable header) — recorded verbatim
 * (spec §15 #12). No contract-status guard: §5.5 documents none, and samples
 * are inert data until an ACTIVE contract's monitor reads them
 * (flag-not-invent).
 *
 * <p>Concurrency: same §10.5 posture as attestations — own transaction, one
 * retry in a fresh transaction on a lost unique/{@code @Version} race.
 */
@Service
public class MetricSampleService {

    private static final Logger log = LoggerFactory.getLogger(MetricSampleService.class);

    public record ReportMetricCommand(UUID contractId, UUID partyId, String metricKey,
                                      double value, String periodKey, String reportedBy,
                                      ContractActor actor) {
    }

    private final ContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final ContractMetricSampleRepository sampleRepository;
    private final ContractEventService eventService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public MetricSampleService(ContractRepository contractRepository,
                               ContractPartyRepository partyRepository,
                               ContractMetricSampleRepository sampleRepository,
                               ContractEventService eventService,
                               PlatformTransactionManager transactionManager,
                               Clock clock,
                               ObjectMapper objectMapper) {
        this.contractRepository = contractRepository;
        this.partyRepository = partyRepository;
        this.sampleRepository = sampleRepository;
        this.eventService = eventService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Governed (§4.2 PEP 2 / §8.3 {@code report_metric}): the aspect evaluates
     * before the self-managed transactions begin — an enforce-mode DENY throws
     * before anything mutates.
     */
    @Governed(actionType = GovernedActions.CONTRACT_REPORT_METRIC)
    public ContractMetricSample report(ReportMetricCommand cmd) {
        validate(cmd);
        try {
            return transactionTemplate.execute(status -> doReport(cmd));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException lostRace) {
            log.info("Metric upsert lost a race [contractId={}, partyId={}, metricKey={}, periodKey={}]"
                            + " — retrying once (§10.5)",
                    cmd.contractId(), cmd.partyId(), cmd.metricKey(), cmd.periodKey(), lostRace);
            return transactionTemplate.execute(status -> doReport(cmd));
        }
    }

    // ------------------------------------------------------------------ internals

    private void validate(ReportMetricCommand cmd) {
        if (cmd.contractId() == null || cmd.partyId() == null) {
            throw new IllegalArgumentException("report requires contractId and partyId");
        }
        if (cmd.metricKey() == null || cmd.metricKey().isBlank()) {
            throw new IllegalArgumentException("report requires metricKey");
        }
        if (cmd.periodKey() == null || cmd.periodKey().isBlank()) {
            throw new IllegalArgumentException("report requires periodKey");
        }
        if (cmd.reportedBy() == null || cmd.reportedBy().isBlank()) {
            throw new IllegalArgumentException("report requires reportedBy");
        }
    }

    private ContractMetricSample doReport(ReportMetricCommand cmd) {
        Contract contract = contractRepository.findById(cmd.contractId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Contract not found: " + cmd.contractId()));
        ContractParty party = partyRepository.findById(cmd.partyId())
                .filter(p -> contract.getId().equals(p.getContractId()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "ContractParty " + cmd.partyId() + " not found on contract " + contract.getId()));

        ContractMetricSample sample = sampleRepository
                .findByContractIdAndMetricKeyAndPeriodKey(
                        contract.getId(), cmd.metricKey(), cmd.periodKey())
                .stream()
                .filter(s -> party.getId().equals(s.getPartyId()))
                .findFirst()
                .orElseGet(ContractMetricSample::new);
        sample.setContractId(contract.getId());
        sample.setPartyId(party.getId());
        sample.setMetricKey(cmd.metricKey());
        sample.setMetricValue(cmd.value());
        sample.setPeriodKey(cmd.periodKey());
        sample.setReportedBy(cmd.reportedBy());
        sample.setReportedAt(clock.instant());
        sample = sampleRepository.save(sample);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("partyId", party.getId().toString());
        payload.put("metricKey", cmd.metricKey());
        payload.put("value", cmd.value());
        payload.put("periodKey", cmd.periodKey());
        payload.put("reportedBy", cmd.reportedBy());
        eventService.append(contract.getId(), ContractEventService.METRIC_REPORTED,
                party.getId(), cmd.actor(), payload);
        return sample;
    }
}
