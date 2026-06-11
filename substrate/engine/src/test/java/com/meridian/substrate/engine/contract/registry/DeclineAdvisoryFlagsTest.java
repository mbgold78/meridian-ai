package com.meridian.substrate.engine.contract.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractEntityLinkRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.NoopContractAccountabilityMirror;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §6 decline-time advisory lanes (engine-spec contracts.md §6.1–§6.3;
 * ADR-002 D6) on the H2 fast lane:
 *
 * <ul>
 *   <li>federation-invitation + NULL {@code review_boundary} ⇒ decline
 *       proceeds (200 semantics) AND rows {@code ALLOW_WITH_FLAG /
 *       REVIEW_BOUNDARY_UNCONFIGURED} (§6.2 null lane, spec §15 #1);</li>
 *   <li>open breaches ⇒ decline proceeds AND rows {@code ALLOW_WITH_FLAG /
 *       OPEN_BREACHES_AT_DECLINE}; the breach row SURVIVES with its status
 *       untouched (§6.3, spec §15 #5);</li>
 *   <li>cross-portfolio contract with no breaches ⇒ NO advisory rows
 *       (matched-nothing ALLOWs are not this component's concern);</li>
 *   <li>NON-null boundary ⇒ no row and no invented window arithmetic — the
 *       §6.2 enforcement lane is the 3c gateway's (flag-not-invent).</li>
 * </ul>
 *
 * <p>DECLINE-never-error: every scenario asserts the decline RETURNED
 * normally with status DECLINED and suspended monitors — the advisory rows
 * are observability, never blockers.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:decline-advisory;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeclineAdvisoryFlagsTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final ContractActor OPERATOR = ContractActor.human("Operating Partner");

    @Autowired private ContractRepository contractRepository;
    @Autowired private ContractPartyRepository partyRepository;
    @Autowired private ContractPartyResponseRepository responseRepository;
    @Autowired private ContractCommitmentRepository commitmentRepository;
    @Autowired private ContractDeclineTriggerRepository triggerRepository;
    @Autowired private ContractEntityLinkRepository entityLinkRepository;
    @Autowired private ContractMonitorRepository monitorRepository;
    @Autowired private ContractClauseGuardRepository guardRepository;
    @Autowired private ContractEventRepository eventRepository;
    @Autowired private ContractEnforcementDecisionRepository decisionRepository;
    @Autowired private ContractBreachRepository breachRepository;
    @Autowired private PlatformTransactionManager txManager;

    private ContractRegistryService service;
    private TransactionTemplate tx;
    private UUID domainId;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ContractProperties properties = new ContractProperties();
        service = new ContractRegistryService(contractRepository, partyRepository,
                responseRepository, commitmentRepository, triggerRepository,
                entityLinkRepository, monitorRepository, guardRepository,
                new ContractEventService(eventRepository, clock),
                new NoopContractAccountabilityMirror(),
                new DeclineAdvisoryFlags(decisionRepository, breachRepository, properties,
                        clock, objectMapper),
                properties, event -> { }, clock, objectMapper);
        tx = new TransactionTemplate(txManager);
        domainId = UUID.randomUUID();
    }

    @Test
    void federationDecline_nullBoundary_rowsReviewBoundaryUnconfigured() {
        Graph g = graph("fi-null-boundary", "federation-invitation", null);

        Contract declined = tx.execute(status -> service.invokeDecline(g.contract.getId(),
                new ContractRegistryService.InvokeDeclineCommand(
                        g.trigger.getId(), "accuracy below agreed threshold", null, OPERATOR)));

        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(monitorRepository.findByContractId(g.contract.getId()))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MonitorStatus.SUSPENDED));

        List<ContractEnforcementDecision> rows = advisoryRows();
        assertThat(rows).hasSize(1);
        ContractEnforcementDecision row = rows.get(0);
        assertThat(row.getDecision()).isEqualTo(GatewayDecision.ALLOW_WITH_FLAG);
        assertThat(row.getReason()).isEqualTo(DeclineAdvisoryFlags.REVIEW_BOUNDARY_UNCONFIGURED);
        assertThat(row.getActionType()).isEqualTo(DeclineAdvisoryFlags.ACTION_TYPE);
        assertThat(row.getActorType()).isEqualTo(PrincipalType.HUMAN);
        assertThat(row.getActorId()).isEqualTo("Operating Partner");
        assertThat(row.getMode()).isEqualTo("shadow");      // §1 default, stamped per ADR D17
        assertThat(row.getContractIds().get(0).asText())
                .isEqualTo(g.contract.getId().toString());
    }

    @Test
    void declineOverOpenBreach_rowsFlagAndBreachSurvives() {
        Graph g = graph("co-open-breach", "cross-portfolio-opportunity", null);
        ContractBreach breach = tx.execute(status -> openBreach(g));

        Contract declined = tx.execute(status -> service.invokeDecline(g.contract.getId(),
                new ContractRegistryService.InvokeDeclineCommand(
                        g.trigger.getId(), "methodology proved insufficient", null, OPERATOR)));

        // Decline returned normally — a legitimate terminal outcome (trap #8).
        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);

        List<ContractEnforcementDecision> rows = advisoryRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReason())
                .isEqualTo(DeclineAdvisoryFlags.OPEN_BREACHES_AT_DECLINE);
        assertThat(rows.get(0).getDecision()).isEqualTo(GatewayDecision.ALLOW_WITH_FLAG);

        // §6.3: the breach SURVIVES decline, status untouched, human verbs remain.
        ContractBreach survived = breachRepository.findById(breach.getId()).orElseThrow();
        assertThat(survived.getStatus()).isEqualTo(BreachStatus.SURFACED);
    }

    @Test
    void crossPortfolioDecline_noBreaches_rowsNothing() {
        Graph g = graph("co-clean", "cross-portfolio-opportunity", null);

        Contract declined = tx.execute(status -> service.invokeDecline(g.contract.getId(),
                new ContractRegistryService.InvokeDeclineCommand(
                        g.trigger.getId(), "alternative consolidation proposed", null, OPERATOR)));

        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(advisoryRows()).isEmpty();
    }

    @Test
    void federationDecline_nonNullBoundary_noRowNoInventedEnforcement() {
        ObjectMapper mapper = new ObjectMapper();
        Graph g = graph("fi-with-boundary", "federation-invitation",
                mapper.createObjectNode()
                        .put("cadence", "QUARTERLY").put("windowDays", 14)
                        .put("anchor", "activation"));

        Contract declined = tx.execute(status -> service.invokeDecline(g.contract.getId(),
                new ContractRegistryService.InvokeDeclineCommand(
                        g.trigger.getId(), "framework changed incompatibly", null, OPERATOR)));

        // 3c gateway seam: boundary arithmetic NOT invented in 3b; decline
        // proceeds (WARN-logged), and no UNCONFIGURED row lies about state.
        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(advisoryRows()).isEmpty();
    }

    // ------------------------------------------------------------------ support

    private record Graph(Contract contract, ContractDeclineTrigger trigger) {
    }

    private Graph graph(String key, String type, com.fasterxml.jackson.databind.JsonNode boundary) {
        return tx.execute(status -> {
            Contract contract = new Contract();
            contract.setDomainId(domainId);
            contract.setContractKey(key);
            contract.setContractType(type);
            contract.setTitle("Advisory-flag test " + key);
            contract.setStatus(ContractStatus.ACTIVE);
            contract.setProposedAt(T0.minusSeconds(86_400));
            contract.setProposedBy("advisory-test");
            contract.setActivatedAt(T0.minusSeconds(3_600));
            contract.setReviewBoundary(boundary);
            Contract saved = contractRepository.save(contract);

            ContractDeclineTrigger trigger = new ContractDeclineTrigger();
            trigger.setContractId(saved.getId());
            trigger.setText("Pre-agreed decline ground for " + key);
            trigger.setSequenceOrder(0);
            ContractDeclineTrigger savedTrigger = triggerRepository.save(trigger);

            ContractMonitor monitor = new ContractMonitor();
            monitor.setContractId(saved.getId());
            monitor.setName("monitor-" + key);
            monitor.setRuleType(MonitorRuleType.UNSTRUCTURED);
            monitor.setCadence("MONTHLY");
            monitor.setStatus(MonitorStatus.ACTIVE);
            monitor.setNextRunAt(T0);
            monitorRepository.save(monitor);

            return new Graph(saved, savedTrigger);
        });
    }

    private ContractBreach openBreach(Graph g) {
        ContractBreach breach = new ContractBreach();
        breach.setContractId(g.contract.getId());
        breach.setPeriodKey("2026-05");
        breach.setStatus(BreachStatus.SURFACED);
        breach.setSeverity(Severity.MEDIUM);
        breach.setSummary("Missing attestation for period 2026-05");
        breach.setDetectedAt(T0.minusSeconds(600));
        breach.setDetectedBy(BreachDetectedBy.MONITOR);
        breach.setSurfacedAt(T0.minusSeconds(300));
        return breachRepository.save(breach);
    }

    private List<ContractEnforcementDecision> advisoryRows() {
        return decisionRepository.findByDomainIdAndDecidedAtAfter(domainId, T0.minusSeconds(1));
    }
}
