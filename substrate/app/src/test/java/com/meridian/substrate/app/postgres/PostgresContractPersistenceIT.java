package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.ContractEntityLink;
import com.meridian.substrate.common.domain.ContractEvent;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractMonitorRun;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.ContractPartyResponse;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract engine chunk 3a-1 Postgres gate (engine-spec contracts.md §14.4,
 * first bullet): V5 (common) + V5_1 (postgresql vendor dir) apply on real
 * Postgres 16; all 14 contract entities persist/read under
 * {@code ddl-auto=validate} (JSON TEXT columns, NUMERIC(18,2),
 * DOUBLE PRECISION, TIMESTAMPTZ, STRING enums, optimistic {@code @Version});
 * repository derived/JPQL queries incl. the {@code PESSIMISTIC_WRITE}
 * variants execute on the PG dialect; the V5_1 open-breach partial unique
 * index backstop fires (ADR D13); and the plain unique idempotency keys hold
 * (uq_attestation_period, uq_metric_sample, uq_party_response,
 * uq_contracts_domain_key).
 *
 * <p>Test content is deliberately SYNTHETIC — fixture-vector parity tests
 * (homepoc contracts.ts verbatim strings) belong to the DemoPack/read-API
 * chunk of 3a per spec §14.1 and must copy from the fixture FILE at
 * test-authoring time.
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresContractPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private ContractRepository contractRepository;
    @Autowired private ContractMonitorRepository contractMonitorRepository;
    @Autowired private ContractClauseGuardRepository contractClauseGuardRepository;
    @Autowired private ContractBreachRepository contractBreachRepository;

    // -----------------------------------------------------------------------
    // 1. Flyway: V5 (common) + V5_1 (postgresql vendor dir) both apply
    // -----------------------------------------------------------------------

    @Test
    void flywayAppliesV5AndPostgresPartialIndexMigration() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history)
                .extracting(row -> String.valueOf(row.get("version")))
                .contains("5", "5.1");
        assertThat(history)
                .allSatisfy(row -> assertThat(row.get("success"))
                        .as("migration V%s succeeded", row.get("version"))
                        .isEqualTo(Boolean.TRUE));

        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_open_breach_per_source'",
                Integer.class);
        assertThat(indexCount)
                .as("V5_1 open-breach partial unique index present on contract_breaches")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 2. All 14 contract entities: persist → flush → clear → reload under
    //    ddl-auto=validate (spec §14.4 round-trip bullet)
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void allFourteenContractEntitiesRoundTripOnPostgres() {
        UUID domainId = UUID.randomUUID();

        Contract contract = newContract(domainId, "it-knowledge-transfer");
        em.persist(contract);

        ContractParty party = new ContractParty();
        party.setContractId(contract.getId());
        party.setPartyLabel("IT Party A (combined obligation)");
        party.setPartyType(ContractPartyType.COMBINED);
        party.setBoundEntityIds(json(
                "[{\"entityType\":\"DOMAIN\",\"entityId\":\"" + domainId + "\",\"slug\":null}]"));
        party.setMcpPrincipalId("agent-principal-1");
        party.setResponseRequired(true);
        party.setSequenceOrder(0);
        em.persist(party);

        ContractPartyResponse response = new ContractPartyResponse();
        response.setPartyId(party.getId());
        response.setContractId(contract.getId());
        response.setResponse(PartyResponse.DECLINED);
        response.setRespondedBy("operator");
        response.setRespondedAt(Instant.parse("2026-04-02T00:00:00Z"));
        response.setDeclineReasoning("Regulatory framework change makes participation untenable.");
        em.persist(response);

        ContractCommitment commitment = new ContractCommitment();
        commitment.setContractId(contract.getId());
        commitment.setPartyId(party.getId());
        commitment.setText("Report monthly progress against the provided template");
        commitment.setDueBy(Instant.parse("2026-09-30T00:00:00Z"));
        commitment.setCadence("MONTHLY");
        commitment.setSequenceOrder(0);
        em.persist(commitment);

        ContractDeclineTrigger trigger = new ContractDeclineTrigger();
        trigger.setContractId(contract.getId());
        trigger.setText("Accuracy threshold below 90%");
        trigger.setPredicate(json("{\"metric\":\"accuracy\",\"lt\":0.90}"));
        trigger.setSequenceOrder(0);
        em.persist(trigger);

        ContractEntityLink link = new ContractEntityLink();
        link.setContractId(contract.getId());
        link.setLinkedEntityType("SUBSIDIARY_SLUG");
        link.setSlug("it-subsidiary-slug");
        link.setLabel("IT Subsidiary");
        em.persist(link);

        ContractClauseGuard guard = new ContractClauseGuard();
        guard.setContractId(contract.getId());
        guard.setCommitmentId(commitment.getId());
        guard.setActionType("agent.promote");
        guard.setEntityType("AGENT_REGISTRATION");
        guard.setEffect(GuardEffect.DENY);
        guard.setPredicate(json("{\"all\":[{\"field\":\"payload.scope\",\"eq\":\"it-scope\"}]}"));
        guard.setDescription("IT deny guard");
        em.persist(guard);

        ContractMonitor monitor = new ContractMonitor();
        monitor.setContractId(contract.getId());
        monitor.setName("it-missing-monthly-report");
        monitor.setRuleType(MonitorRuleType.ATTESTATION_CADENCE);
        monitor.setRuleSpec(json("{\"commitmentIds\":[\"" + commitment.getId() + "\"],\"graceDays\":7}"));
        monitor.setCadence("MONTHLY");
        monitor.setGraceDays(7);
        monitor.setSurfaceTo("OPERATING_PARTNER_DASHBOARD");
        monitor.setSeverity(Severity.HIGH);
        em.persist(monitor);

        ContractMonitorRun run = new ContractMonitorRun();
        run.setMonitorId(monitor.getId());
        run.setStartedAt(Instant.parse("2026-05-08T00:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-05-08T00:00:01Z"));
        run.setOutcome(MonitorRunOutcome.NOT_DUE);
        run.setPeriodKey("2026-04");
        run.setDetails(json("{\"rule\":\"ATTESTATION_CADENCE\",\"truncated\":false}"));
        em.persist(run);

        ContractAttestation attestation = new ContractAttestation();
        attestation.setCommitmentId(commitment.getId());
        attestation.setContractId(contract.getId());
        attestation.setPartyId(party.getId());
        attestation.setPeriodKey("2026-05");
        attestation.setAttestedBy("operator");
        attestation.setAttestedAt(Instant.parse("2026-05-15T00:00:00Z"));
        attestation.setComplianceClaim(ComplianceClaim.ON_TRACK);
        attestation.setStatement("On schedule.");
        attestation.setEvidenceRefs(json("[\"" + UUID.randomUUID() + "\"]"));
        attestation.setSubmittedVia("MCP");
        em.persist(attestation);

        ContractMetricSample sample = new ContractMetricSample();
        sample.setContractId(contract.getId());
        sample.setPartyId(party.getId());
        sample.setMetricKey("utilization_share");
        sample.setMetricValue(0.42);
        sample.setPeriodKey("2026-Q2");
        sample.setReportedBy("agent-principal-1");
        sample.setReportedAt(Instant.parse("2026-07-01T00:00:00Z"));
        em.persist(sample);

        ContractBreach breach = new ContractBreach();
        breach.setContractId(contract.getId());
        breach.setCommitmentId(commitment.getId());
        breach.setMonitorId(monitor.getId());
        breach.setPeriodKey("2026-04");
        breach.setStatus(BreachStatus.DETECTED);
        breach.setSeverity(Severity.HIGH);
        breach.setSummary("Monthly report missing past grace");
        breach.setDetectedAt(Instant.parse("2026-05-08T00:00:00Z"));
        breach.setDetectedBy(BreachDetectedBy.MONITOR);
        breach.setLastConfirmedAt(Instant.parse("2026-05-08T00:00:00Z"));
        em.persist(breach);

        ContractEvent event = new ContractEvent();
        event.setContractId(contract.getId());
        event.setEventType("BREACH_DETECTED");
        event.setRefId(breach.getId());
        event.setActorType(ContractActorType.MONITOR);
        event.setActorId("it-missing-monthly-report");
        event.setPayload(json("{\"periodKey\":\"2026-04\"}"));
        event.setOccurredAt(Instant.parse("2026-05-08T00:00:00Z"));
        em.persist(event);

        ContractEnforcementDecision decision = new ContractEnforcementDecision();
        decision.setDomainId(domainId);
        decision.setActionType("agent.promote");
        decision.setActorType(PrincipalType.AGENT);
        decision.setActorId("agent-principal-1");
        decision.setDecision(GatewayDecision.WOULD_DENY);
        decision.setContractIds(json("[\"" + contract.getId() + "\"]"));
        decision.setClauseIds(json("[\"" + guard.getId() + "\"]"));
        decision.setReason("guard matched in shadow mode");
        decision.setLatencyMs(3);
        decision.setMode("shadow");
        decision.setDecidedAt(Instant.parse("2026-05-09T00:00:00Z"));
        em.persist(decision);

        em.flush();
        em.clear();

        Contract dbContract = em.find(Contract.class, contract.getId());
        assertThat(dbContract.getContractKey()).isEqualTo("it-knowledge-transfer");
        assertThat(dbContract.getContractType()).isEqualTo("cross-portfolio-opportunity");
        assertThat(dbContract.getStatus()).isEqualTo(ContractStatus.PROPOSED);
        assertThat(dbContract.getEstimatedValue()).isEqualTo("$1M annually (synthetic)");
        assertThat(dbContract.getEstimatedValueAmount()).isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(dbContract.getProposedAt()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(dbContract.getReviewBoundary()).isNull();   // unenforced-when-null (§6.2)
        assertThat(dbContract.getMetadata()).isEqualTo(json("{\"origin\":\"contract-it\"}"));
        assertThat(dbContract.getVersion()).isZero();
        assertThat(dbContract.getCreatedBy()).isEqualTo("system");

        ContractParty dbParty = em.find(ContractParty.class, party.getId());
        assertThat(dbParty.getPartyType()).isEqualTo(ContractPartyType.COMBINED);
        assertThat(dbParty.isResponseRequired()).isTrue();
        assertThat(dbParty.getBoundEntityIds().get(0).get("entityType").asText()).isEqualTo("DOMAIN");

        ContractPartyResponse dbResponse = em.find(ContractPartyResponse.class, response.getId());
        assertThat(dbResponse.getResponse()).isEqualTo(PartyResponse.DECLINED);
        assertThat(dbResponse.getDeclineReasoning()).contains("untenable");
        assertThat(dbResponse.getRespondedAt()).isEqualTo(Instant.parse("2026-04-02T00:00:00Z"));

        ContractCommitment dbCommitment = em.find(ContractCommitment.class, commitment.getId());
        assertThat(dbCommitment.getComplianceState()).isEqualTo(CommitmentComplianceState.UNEVALUATED);
        assertThat(dbCommitment.getCadence()).isEqualTo("MONTHLY");
        assertThat(dbCommitment.getDueBy()).isEqualTo(Instant.parse("2026-09-30T00:00:00Z"));

        ContractDeclineTrigger dbTrigger = em.find(ContractDeclineTrigger.class, trigger.getId());
        assertThat(dbTrigger.isArmed()).isFalse();
        assertThat(dbTrigger.getPredicate()).isEqualTo(json("{\"metric\":\"accuracy\",\"lt\":0.90}"));

        ContractEntityLink dbLink = em.find(ContractEntityLink.class, link.getId());
        assertThat(dbLink.getLinkedEntityType()).isEqualTo("SUBSIDIARY_SLUG");
        assertThat(dbLink.getLinkedEntityId()).isNull();
        assertThat(dbLink.getSlug()).isEqualTo("it-subsidiary-slug");

        ContractClauseGuard dbGuard = em.find(ContractClauseGuard.class, guard.getId());
        assertThat(dbGuard.getEffect()).isEqualTo(GuardEffect.DENY);
        assertThat(dbGuard.isActive()).isFalse();   // guards live only while ACTIVE
        assertThat(dbGuard.getCommitmentId()).isEqualTo(commitment.getId());

        ContractMonitor dbMonitor = em.find(ContractMonitor.class, monitor.getId());
        assertThat(dbMonitor.getRuleType()).isEqualTo(MonitorRuleType.ATTESTATION_CADENCE);
        assertThat(dbMonitor.getStatus()).isEqualTo(MonitorStatus.SUSPENDED);   // created SUSPENDED
        assertThat(dbMonitor.getGraceDays()).isEqualTo(7);
        assertThat(dbMonitor.getNextRunAt()).isNull();

        ContractMonitorRun dbRun = em.find(ContractMonitorRun.class, run.getId());
        assertThat(dbRun.getOutcome()).isEqualTo(MonitorRunOutcome.NOT_DUE);
        assertThat(dbRun.getPeriodKey()).isEqualTo("2026-04");
        assertThat(dbRun.getDetails().get("truncated").asBoolean()).isFalse();

        ContractAttestation dbAttestation = em.find(ContractAttestation.class, attestation.getId());
        assertThat(dbAttestation.getComplianceClaim()).isEqualTo(ComplianceClaim.ON_TRACK);
        assertThat(dbAttestation.getSubmittedVia()).isEqualTo("MCP");
        assertThat(dbAttestation.getPeriodKey()).isEqualTo("2026-05");

        ContractMetricSample dbSample = em.find(ContractMetricSample.class, sample.getId());
        assertThat(dbSample.getMetricValue()).isEqualTo(0.42);
        assertThat(dbSample.getPeriodKey()).isEqualTo("2026-Q2");

        ContractBreach dbBreach = em.find(ContractBreach.class, breach.getId());
        assertThat(dbBreach.getStatus()).isEqualTo(BreachStatus.DETECTED);
        assertThat(dbBreach.getDetectedBy()).isEqualTo(BreachDetectedBy.MONITOR);
        assertThat(dbBreach.getResolution()).isNull();

        ContractEvent dbEvent = em.find(ContractEvent.class, event.getId());
        assertThat(dbEvent.getActorType()).isEqualTo(ContractActorType.MONITOR);
        assertThat(dbEvent.getRefId()).isEqualTo(breach.getId());
        assertThat(dbEvent.getPayload().get("periodKey").asText()).isEqualTo("2026-04");

        ContractEnforcementDecision dbDecision = em.find(ContractEnforcementDecision.class, decision.getId());
        assertThat(dbDecision.getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(dbDecision.getActorType()).isEqualTo(PrincipalType.AGENT);
        assertThat(dbDecision.getMode()).isEqualTo("shadow");
        assertThat(dbDecision.getLatencyMs()).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // 3. Repository queries — derived, JPQL, and PESSIMISTIC_WRITE on PG
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void contractRepositoryQueriesAndPessimisticLocksExecuteOnPostgres() {
        UUID domainId = UUID.randomUUID();
        Contract contract = contractRepository.save(newContract(domainId, "it-query-contract"));

        ContractMonitor monitor = new ContractMonitor();
        monitor.setContractId(contract.getId());
        monitor.setName("it-due-monitor");
        monitor.setRuleType(MonitorRuleType.UNSTRUCTURED);
        monitor.setCadence("MONTHLY");
        monitor.setStatus(MonitorStatus.ACTIVE);
        monitor.setNextRunAt(Instant.parse("2026-05-01T00:00:00Z"));
        contractMonitorRepository.save(monitor);

        ContractClauseGuard guard = new ContractClauseGuard();
        guard.setContractId(contract.getId());
        guard.setActionType("agent.promote");
        guard.setEffect(GuardEffect.DENY);
        guard.setActive(true);
        guard.setDescription("IT active deny guard");
        contractClauseGuardRepository.save(guard);

        ContractBreach breach = new ContractBreach();
        breach.setContractId(contract.getId());
        breach.setMonitorId(monitor.getId());
        breach.setPeriodKey("2026-04");
        breach.setStatus(BreachStatus.DETECTED);
        breach.setSummary("IT open breach");
        breach.setDetectedAt(Instant.parse("2026-05-08T00:00:00Z"));
        breach.setDetectedBy(BreachDetectedBy.MONITOR);
        contractBreachRepository.save(breach);

        em.flush();
        em.clear();

        assertThat(contractRepository.findByDomainId(domainId)).hasSize(1);
        assertThat(contractRepository.findByDomainIdAndStatus(domainId, ContractStatus.PROPOSED)).hasSize(1);
        assertThat(contractRepository.findByDomainIdAndStatus(domainId, ContractStatus.ACTIVE)).isEmpty();
        assertThat(contractRepository.findByDomainIdAndContractKey(domainId, "it-query-contract")).isPresent();
        // SELECT … FOR UPDATE — the lifecycle-transition serialization point (§10.1)
        assertThat(contractRepository.findByIdForUpdate(contract.getId())).isPresent();

        assertThat(contractClauseGuardRepository.findByActionTypeAndActiveTrue("agent.promote"))
                .extracting(ContractClauseGuard::getContractId)
                .contains(contract.getId());

        assertThat(contractMonitorRepository.findByStatusAndNextRunAtBefore(
                MonitorStatus.ACTIVE, Instant.parse("2026-06-01T00:00:00Z")))
                .extracting(ContractMonitor::getName)
                .contains("it-due-monitor");
        // H2-fallback claim lane; PG native SKIP LOCKED claim lands with the 3b sweep (§10.3)
        assertThat(contractMonitorRepository.findByStatusAndNextRunAtBeforeForUpdate(
                MonitorStatus.ACTIVE, Instant.parse("2026-06-01T00:00:00Z")))
                .extracting(ContractMonitor::getName)
                .contains("it-due-monitor");

        assertThat(contractBreachRepository.findByContractIdAndStatusIn(
                contract.getId(),
                List.of(BreachStatus.DETECTED, BreachStatus.SURFACED, BreachStatus.ACKNOWLEDGED)))
                .hasSize(1);
        // ADR D13 dedup lookup: NULL commitment matches NULL column; FOR UPDATE on PG
        assertThat(contractBreachRepository.findOpenBySourceForUpdate(
                contract.getId(), monitor.getId(), null, "2026-04")).isPresent();
        assertThat(contractBreachRepository.findOpenBySourceForUpdate(
                contract.getId(), null, null, null)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // 4. V5_1 partial unique index: open-breach dedup backstop fires; terminal
    //    statuses are exempt from the predicate (ADR D13)
    // -----------------------------------------------------------------------

    @Test
    void openBreachPartialUniqueIndexBackstopFires() {
        UUID contractId = UUID.randomUUID();
        UUID monitorId = UUID.randomUUID();
        UUID commitmentId = UUID.randomUUID();

        insertBreachRow(contractId, monitorId, commitmentId, "2026-04", "DETECTED");

        // same (contract, monitor, commitment, period) while open → backstop fires
        assertThatThrownBy(() ->
                insertBreachRow(contractId, monitorId, commitmentId, "2026-04", "SURFACED"))
                .as("duplicate OPEN breach per source violates uq_open_breach_per_source")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_open_breach_per_source");

        // NULL components COALESCE to the sentinel — two NULL-monitor open rows collide too
        UUID contract2 = UUID.randomUUID();
        insertBreachRow(contract2, null, commitmentId, "2026-04", "DETECTED");
        assertThatThrownBy(() ->
                insertBreachRow(contract2, null, commitmentId, "2026-04", "DETECTED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_open_breach_per_source");

        // terminal statuses sit outside the partial predicate — duplicates legal
        insertBreachRow(contractId, monitorId, commitmentId, "2026-04", "REMEDIATED");
        insertBreachRow(contractId, monitorId, commitmentId, "2026-04", "WAIVED");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_breaches WHERE contract_id = ?",
                Integer.class, contractId);
        assertThat(rows).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // 5. Plain unique idempotency keys (ADR D13; §10.2/§10.5; ADR D8 slug)
    // -----------------------------------------------------------------------

    @Test
    void idempotencyUniqueKeysAreBackstoppedByConstraints() {
        UUID domainId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        UUID commitmentId = UUID.randomUUID();

        // uq_contracts_domain_key — slug unique per domain (ADR D8)
        insertContractRow(domainId, contractId, "it-dup-key");
        assertThatThrownBy(() -> insertContractRow(domainId, UUID.randomUUID(), "it-dup-key"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_contracts_domain_key");

        // uq_party_response — one response per party (§10.2)
        insertResponseRow(partyId, contractId);
        assertThatThrownBy(() -> insertResponseRow(partyId, contractId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_party_response");

        // uq_attestation_period — upsert key (ADR D13)
        insertAttestationRow(commitmentId, contractId, partyId, "2026-05");
        assertThatThrownBy(() -> insertAttestationRow(commitmentId, contractId, partyId, "2026-05"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_attestation_period");

        // uq_metric_sample — idempotent upsert key (ADR D11/D13)
        insertMetricSampleRow(contractId, partyId, "utilization_share", "2026-Q2");
        assertThatThrownBy(() -> insertMetricSampleRow(contractId, partyId, "utilization_share", "2026-Q2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_metric_sample");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static Contract newContract(UUID domainId, String contractKey) {
        Contract contract = new Contract();
        contract.setDomainId(domainId);
        contract.setContractKey(contractKey);
        contract.setContractType("cross-portfolio-opportunity");
        contract.setTitle("Synthetic IT contract");
        contract.setContext("Synthetic context framing");
        contract.setMonitorText("Synthetic monitor prose");
        contract.setMonitorMechanism("Synthetic mechanism prose");
        contract.setEstimatedValue("$1M annually (synthetic)");
        contract.setEstimatedValueAmount(new BigDecimal("1000000.00"));
        contract.setStatus(ContractStatus.PROPOSED);
        contract.setProposedAt(Instant.parse("2026-04-01T00:00:00Z"));
        contract.setProposedBy("operator");
        contract.setMetadata(json("{\"origin\":\"contract-it\"}"));
        return contract;
    }

    private void insertContractRow(UUID domainId, UUID id, String contractKey) {
        jdbcTemplate.update(
                "INSERT INTO contracts (id, domain_id, contract_key, contract_type, title, status, "
                        + "proposed_at, proposed_by, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 'cross-portfolio-opportunity', 'IT raw contract', 'PROPOSED', "
                        + "CURRENT_TIMESTAMP, 'contract-it', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'contract-it', 0)",
                id, domainId, contractKey);
    }

    private void insertResponseRow(UUID partyId, UUID contractId) {
        jdbcTemplate.update(
                "INSERT INTO contract_party_responses (id, party_id, contract_id, response, responded_by, "
                        + "responded_at, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 'ACCEPTED', 'contract-it', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'contract-it', 0)",
                UUID.randomUUID(), partyId, contractId);
    }

    private void insertAttestationRow(UUID commitmentId, UUID contractId, UUID partyId, String periodKey) {
        jdbcTemplate.update(
                "INSERT INTO contract_attestations (id, commitment_id, contract_id, party_id, period_key, "
                        + "attested_by, attested_at, compliance_claim, submitted_via, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'contract-it', CURRENT_TIMESTAMP, 'ON_TRACK', 'API', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'contract-it', 0)",
                UUID.randomUUID(), commitmentId, contractId, partyId, periodKey);
    }

    private void insertMetricSampleRow(UUID contractId, UUID partyId, String metricKey, String periodKey) {
        jdbcTemplate.update(
                "INSERT INTO contract_metric_samples (id, contract_id, party_id, metric_key, metric_value, "
                        + "period_key, reported_by, reported_at, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, 0.42, ?, 'contract-it', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'contract-it', 0)",
                UUID.randomUUID(), contractId, partyId, metricKey, periodKey);
    }

    private void insertBreachRow(UUID contractId, UUID monitorId, UUID commitmentId,
                                 String periodKey, String status) {
        jdbcTemplate.update(
                "INSERT INTO contract_breaches (id, contract_id, commitment_id, monitor_id, period_key, "
                        + "status, summary, detected_at, detected_by, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'IT breach row', CURRENT_TIMESTAMP, 'MONITOR', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'contract-it', 0)",
                UUID.randomUUID(), contractId, commitmentId, monitorId, periodKey, status);
    }

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad test JSON literal: " + content, e);
        }
    }
}
