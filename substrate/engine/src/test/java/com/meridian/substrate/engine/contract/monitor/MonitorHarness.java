package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractMonitorRunRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import com.meridian.substrate.engine.contract.ContractAccountabilityMirror;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Hand-wired 3b service graph for the H2 fast lane (engine-spec contracts.md
 * §14.3) — EngineContractJpaTestConfig boots entities+repos only; services
 * are constructed here without proxies, so verbs run inside explicit
 * {@link TransactionTemplate} transactions (PESSIMISTIC_WRITE needs an active
 * transaction; ContractRegistryServiceStateMachineTest precedent).
 *
 * <p>Testcontainers-Postgres in CI remains the acceptance gate for lock /
 * partial-index / SKIP LOCKED semantics — H2-green is never acceptance.
 */
final class MonitorHarness {

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final MutableClock clock;
    final ContractProperties properties = new ContractProperties();
    final List<Object> published = new ArrayList<>();
    final RecordingMirror mirror = new RecordingMirror();
    final AtomicReference<McpLivenessProbe> probe = new AtomicReference<>(uri -> true);

    final ContractRepository contracts;
    final ContractPartyRepository parties;
    final ContractCommitmentRepository commitments;
    final ContractDeclineTriggerRepository triggers;
    final ContractMonitorRepository monitors;
    final ContractMonitorRunRepository runs;
    final ContractAttestationRepository attestations;
    final ContractMetricSampleRepository metricSamples;
    final ContractBreachRepository breaches;
    final ContractEventRepository events;
    final ReaderRegistrationRepository readers;

    final ContractEventService eventService;
    final EvidenceRefValidator evidenceRefValidator;
    final BreachService breachService;
    final AttestationCadenceEvaluator attestationCadenceEvaluator;
    final MetricThresholdEvaluator metricThresholdEvaluator;
    final McpProbeEvaluator mcpProbeEvaluator;
    final UnstructuredEvaluator unstructuredEvaluator;
    final MonitorRunService runService;
    final AttestationService attestationService;
    final MetricSampleService metricSampleService;
    final DeclineTriggerArmingService armingService;
    final MonitorSweepClaims claims;
    final ContractMonitorScheduler scheduler;

    private final TransactionTemplate tx;
    private int keySeq;

    MonitorHarness(ApplicationContext ctx, EntityManager entityManager, Instant start) {
        this.clock = new MutableClock(start);
        this.contracts = ctx.getBean(ContractRepository.class);
        this.parties = ctx.getBean(ContractPartyRepository.class);
        this.commitments = ctx.getBean(ContractCommitmentRepository.class);
        this.triggers = ctx.getBean(ContractDeclineTriggerRepository.class);
        this.monitors = ctx.getBean(ContractMonitorRepository.class);
        this.runs = ctx.getBean(ContractMonitorRunRepository.class);
        this.attestations = ctx.getBean(ContractAttestationRepository.class);
        this.metricSamples = ctx.getBean(ContractMetricSampleRepository.class);
        this.breaches = ctx.getBean(ContractBreachRepository.class);
        this.events = ctx.getBean(ContractEventRepository.class);
        this.readers = ctx.getBean(ReaderRegistrationRepository.class);
        PlatformTransactionManager txManager = ctx.getBean(PlatformTransactionManager.class);
        this.tx = new TransactionTemplate(txManager);

        this.eventService = new ContractEventService(events, clock);
        this.evidenceRefValidator = new EvidenceRefValidator(
                ctx.getBean(EvidenceRepository.class), ctx.getBean(DocumentRegistryRepository.class));
        this.breachService = new BreachService(breaches, commitments, contracts, eventService,
                mirror, evidenceRefValidator, published::add, clock, mapper);
        this.attestationCadenceEvaluator =
                new AttestationCadenceEvaluator(commitments, attestations, mapper);
        this.metricThresholdEvaluator = new MetricThresholdEvaluator(metricSamples, mapper);
        this.mcpProbeEvaluator = new McpProbeEvaluator(
                readers, uri -> probe.get().isReachable(uri), mapper);
        this.unstructuredEvaluator = new UnstructuredEvaluator(mapper);
        this.runService = new MonitorRunService(
                List.of(attestationCadenceEvaluator, metricThresholdEvaluator,
                        mcpProbeEvaluator, unstructuredEvaluator),
                monitors, runs, commitments, breaches, breachService, eventService,
                properties, published::add, SweepJitter.none(), clock, mapper);
        this.attestationService = new AttestationService(commitments, contracts, attestations,
                monitors, breachService, eventService, evidenceRefValidator, txManager, clock, mapper);
        this.metricSampleService = new MetricSampleService(contracts, parties, metricSamples,
                eventService, txManager, clock, mapper);
        this.armingService = new DeclineTriggerArmingService(contracts, triggers, metricSamples,
                eventService, properties, published::add, txManager, clock, mapper);
        this.claims = new MonitorSweepClaims(entityManager, monitors);
        this.scheduler = new ContractMonitorScheduler(monitors, claims, runService, armingService,
                breachService, properties, clock, txManager);
    }

    // ------------------------------------------------------------ transactions

    void inTx(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    // ---------------------------------------------------------------- builders

    Contract activeContract() {
        return contract(ContractStatus.ACTIVE);
    }

    Contract contract(ContractStatus status) {
        Contract contract = new Contract();
        contract.setDomainId(UUID.randomUUID());
        contract.setContractKey("test-contract-" + (++keySeq));
        contract.setContractType("cross-portfolio-opportunity");
        contract.setTitle("Synthetic 3b test contract " + keySeq);
        contract.setEstimatedValueAmount(BigDecimal.ONE);
        contract.setStatus(status);
        contract.setProposedAt(clock.instant());
        contract.setProposedBy("test-operator");
        if (status == ContractStatus.ACTIVE) {
            contract.setActivatedAt(clock.instant());
        }
        return contracts.save(contract);
    }

    ContractParty party(Contract contract, String label) {
        ContractParty party = new ContractParty();
        party.setContractId(contract.getId());
        party.setPartyLabel(label);
        party.setPartyType(ContractPartyType.ORG_UNIT);
        party.setResponseRequired(true);
        party.setSequenceOrder(0);
        return parties.save(party);
    }

    ContractCommitment commitment(Contract contract, ContractParty party, String cadence) {
        ContractCommitment commitment = new ContractCommitment();
        commitment.setContractId(contract.getId());
        commitment.setPartyId(party.getId());
        commitment.setText("Synthetic commitment (" + cadence + ")");
        commitment.setCadence(cadence);
        commitment.setSequenceOrder(0);
        return commitments.save(commitment);
    }

    ContractMonitor monitor(Contract contract, MonitorRuleType ruleType, String cadence,
                            int graceDays, JsonNode ruleSpec, MonitorStatus status) {
        ContractMonitor monitor = new ContractMonitor();
        monitor.setContractId(contract.getId());
        monitor.setName("synthetic-" + ruleType.name().toLowerCase() + "-" + (++keySeq));
        monitor.setRuleType(ruleType);
        monitor.setCadence(cadence);
        monitor.setGraceDays(graceDays);
        monitor.setRuleSpec(ruleSpec);
        monitor.setSeverity(Severity.MEDIUM);
        monitor.setStatus(status);
        if (status == MonitorStatus.ACTIVE) {
            monitor.setNextRunAt(clock.instant().minusSeconds(60));
        }
        return monitors.save(monitor);
    }

    ContractDeclineTrigger trigger(Contract contract, JsonNode predicate) {
        ContractDeclineTrigger trigger = new ContractDeclineTrigger();
        trigger.setContractId(contract.getId());
        trigger.setText("Synthetic decline trigger");
        trigger.setPredicate(predicate);
        trigger.setSequenceOrder(0);
        return triggers.save(trigger);
    }

    List<ContractBreach> allBreaches() {
        return breaches.findAll();
    }

    /**
     * Contract-scoped breach rows (all statuses). Test classes share one H2
     * DB per context, so cross-method assertions must scope to the method's
     * own contract.
     */
    List<ContractBreach> breachesOf(Contract contract) {
        return breaches.findAll().stream()
                .filter(b -> contract.getId().equals(b.getContractId()))
                .toList();
    }

    long eventCount(UUID contractId, String eventType) {
        return events.findByContractIdOrderByOccurredAtAsc(contractId).stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .count();
    }

    // ------------------------------------------------------------ inner helpers

    /** Settable clock (S15 determinism + refresh-timestamp assertions). */
    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void set(Instant next) {
            this.instant = next;
        }

        void advance(Duration delta) {
            this.instant = this.instant.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Records ADR D14 mirror anchors. */
    static final class RecordingMirror implements ContractAccountabilityMirror {

        final List<Contract> activated = new ArrayList<>();
        final List<Contract> declined = new ArrayList<>();
        final List<ContractBreach> waived = new ArrayList<>();

        @Override
        public void onActivated(Contract contract) {
            activated.add(contract);
        }

        @Override
        public void onDeclined(Contract contract) {
            declined.add(contract);
        }

        @Override
        public void onBreachWaived(Contract contract, ContractBreach breach) {
            waived.add(breach);
        }
    }
}
