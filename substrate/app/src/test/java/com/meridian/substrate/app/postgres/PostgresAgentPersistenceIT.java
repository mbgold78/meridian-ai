package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.AgentComplianceReview;
import com.meridian.substrate.common.domain.AgentCriteriaGate;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentMetricSample;
import com.meridian.substrate.common.domain.AgentNotification;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRateCounter;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.AgentThrottleBudget;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentReadinessSource;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent registry phase-6a-part-1 Postgres gate (engine-spec agents.md §16.2
 * test plan #17 + the SQL half of #6): V6 (common) + V6_1 (postgresql vendor
 * dir) apply on real Postgres 16; all 11 agent entities persist/read under
 * {@code ddl-auto=validate} (JSON TEXT columns, DOUBLE PRECISION,
 * TIMESTAMPTZ, STRING enums, optimistic {@code @Version}); repository
 * derived/JPQL queries incl. the PESSIMISTIC_WRITE variant and the atomic
 * counter increment execute on the PG dialect; the V6_1 append-only trigger
 * raises on direct JDBC UPDATE/DELETE of {@code agent_status_events}; the
 * V6_1 partial uniques fire (open-intervention-per-source,
 * active-budget-per-kind, principal-when-present); and the plain unique
 * idempotency keys hold incl. UNIQUE-with-NULL {@code dedupe_key} (trap #4).
 *
 * <p>Test content is deliberately SYNTHETIC — fixture-vector parity tests
 * (subsidiaries/*.ts verbatim strings) belong to the DemoPack/read-API slice
 * of 6a per spec §15 and must copy from the fixture FILES at authoring time.
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4) — the
 * trigger, partial uniques, FOR UPDATE and counter increments are CI-verified
 * ONLY (spec §18).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresAgentPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private AgentRegistrationRepository registrationRepository;
    @Autowired private AgentStatusEventRepository eventRepository;
    @Autowired private AgentRateCounterRepository rateCounterRepository;

    // -----------------------------------------------------------------------
    // 1. Flyway: V6 (common) + V6_1 (postgresql vendor dir) both apply
    // -----------------------------------------------------------------------

    @Test
    void flywayAppliesV6AndPostgresVendorMigration() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history)
                .extracting(row -> String.valueOf(row.get("version")))
                .contains("6", "6.1");
        assertThat(history)
                .allSatisfy(row -> assertThat(row.get("success"))
                        .as("migration V%s succeeded", row.get("version"))
                        .isEqualTo(Boolean.TRUE));

        // V6_1 partial uniques present
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE indexname IN "
                        + "('uq_open_intervention_per_source','uq_active_budget_per_kind','uq_agent_principal')",
                String.class);
        assertThat(indexNames).containsExactlyInAnyOrder(
                "uq_open_intervention_per_source", "uq_active_budget_per_kind", "uq_agent_principal");

        // V6_1 append-only trigger present on agent_status_events
        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'trg_ase_append_only'", Integer.class);
        assertThat(triggerCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 2. All 11 agent entities: persist → flush → clear → reload under
    //    ddl-auto=validate (test plan #17 round-trip)
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void allElevenAgentEntitiesRoundTripOnPostgres() {
        UUID domainId = UUID.randomUUID();

        AgentRegistration reg = newRegistration(domainId, "it-agent");
        reg.setMcpPrincipalId("it-principal-1");
        reg.setTargetStepIds(json("[\"" + UUID.randomUUID() + "\"]"));
        reg.setAgentReadinessScore(72);
        reg.setReadinessSource(AgentReadinessSource.OPERATOR_ASSERTED);
        reg.setRequiredSystemAccess(json("[\"EHR (read)\"]"));
        reg.setScheduleWindow(json("{\"start\":\"22:00\",\"end\":\"05:00\"}"));
        reg.setSamplingPct(25);
        em.persist(reg);

        AgentOversightLink link = new AgentOversightLink();
        link.setAgentRegistrationId(reg.getId());
        link.setPosition(1);
        link.setLabel("IT Oversight Lead");
        em.persist(link);

        AgentStatusEvent event = new AgentStatusEvent();
        event.setAgentRegistrationId(reg.getId());
        event.setDomainId(domainId);
        event.setEventSeq(1);
        event.setEventType(AgentEventType.DEPLOYED);
        event.setActorType(ContractActorType.HUMAN);
        event.setActorLabel("IT Operator");
        event.setPayload(json("{\"criteriaDigest\":\"abc\"}"));
        event.setEvidenceRefs(json("[\"" + UUID.randomUUID() + "\"]"));
        event.setIdempotencyKey("it-deploy-1");
        event.setOccurredAt(Instant.parse("2026-06-01T00:00:00Z"));
        em.persist(event);

        AgentIntervention intervention = new AgentIntervention();
        intervention.setAgentRegistrationId(reg.getId());
        intervention.setDomainId(domainId);
        intervention.setStatus(AgentInterventionStatus.OPEN);
        intervention.setTriggerKind(AgentInterventionTrigger.HUMAN);
        intervention.setReason("Drift suspected on the IT cohort");
        intervention.setOpenedByLabel("IT Operations Lead");
        intervention.setOpenedAt(Instant.parse("2026-06-02T00:00:00Z"));
        em.persist(intervention);

        AgentComplianceReview review = new AgentComplianceReview();
        review.setAgentRegistrationId(reg.getId());
        review.setReviewKind(AgentReviewKind.PROMOTION);
        review.setRequestedAt(Instant.parse("2026-06-03T00:00:00Z"));
        review.setRequestedByLabel("IT Operator");
        review.setReviewerLabel("IT Compliance Officer");
        review.setDecidedAt(Instant.parse("2026-06-04T00:00:00Z"));
        review.setDecision(AgentReviewDecision.NEEDS_MORE_TIME);
        review.setRationale("Shadow window too short for the IT cohort");
        em.persist(review);

        AgentCriteriaGate gate = new AgentCriteriaGate();
        gate.setAgentRegistrationId(reg.getId());
        gate.setSequenceOrder(0);
        gate.setGateText("target 90% IT accuracy");
        gate.setRuleType(AgentGateRuleType.STRUCTURED);
        gate.setPredicate(json("{\"metric\":\"it_accuracy\",\"gte\":0.90}"));
        gate.setCadence("MONTHLY");
        em.persist(gate);

        AgentMetricSample sample = new AgentMetricSample();
        sample.setAgentRegistrationId(reg.getId());
        sample.setMetricKey("it_accuracy");
        sample.setMetricValue(0.93);
        sample.setPeriodKey("2026-05");
        sample.setReportedByPrincipal("it-principal-1");
        sample.setReportedAt(Instant.parse("2026-06-01T00:00:00Z"));
        em.persist(sample);

        AgentThrottleBudget budget = new AgentThrottleBudget();
        budget.setAgentRegistrationId(reg.getId());
        budget.setBudgetKind("ACTIONS_PER_WINDOW");
        budget.setLimitValue(100.0);
        budget.setWindowMinutes(60);
        budget.setSetByLabel("IT Operator");
        budget.setSetAt(Instant.parse("2026-06-01T00:00:00Z"));
        em.persist(budget);

        AgentRateCounter counter = new AgentRateCounter();
        counter.setAgentRegistrationId(reg.getId());
        counter.setWindowStart(Instant.parse("2026-06-01T10:00:00Z"));
        counter.setActionCount(3);
        em.persist(counter);

        AgentSighting sighting = new AgentSighting();
        sighting.setAgentRegistrationId(reg.getId());
        sighting.setSeenAt(Instant.parse("2026-06-01T10:15:00Z"));
        sighting.setChannel(AgentSightingChannel.HEARTBEAT);
        sighting.setDetail("heartbeat: nominal");
        sighting.setBucketKey("2026-06-01T10");
        em.persist(sighting);

        AgentNotification notification = new AgentNotification();
        notification.setDomainId(domainId);
        notification.setAgentRegistrationId(reg.getId());
        notification.setEventId(event.getId());
        notification.setNotificationType(AgentNotificationType.AGENT_DEPLOYED);
        notification.setRecipientLabel("IT Oversight Lead");
        notification.setTone("info");
        notification.setPayload(json("{\"agentKey\":\"it-agent\"}"));
        notification.setDedupeKey("it-deploy-notify-1");
        em.persist(notification);

        em.flush();
        em.clear();

        AgentRegistration dbReg = em.find(AgentRegistration.class, reg.getId());
        assertThat(dbReg.getAgentKey()).isEqualTo("it-agent");
        assertThat(dbReg.getExecutionLocus()).isEqualTo(AgentExecutionLocus.CUSTOMER_SYSTEM);
        assertThat(dbReg.getSupervisoryModel()).isEqualTo(AgentSupervisoryModel.HUMAN_IN_LOOP);
        assertThat(dbReg.getOperationalMode()).isEqualTo(AgentOperationalMode.SHADOW);
        assertThat(dbReg.isReadOnly()).isTrue();
        assertThat(dbReg.getAgentReadinessScore()).isEqualTo(72);
        assertThat(dbReg.getReadinessSource()).isEqualTo(AgentReadinessSource.OPERATOR_ASSERTED);
        assertThat(dbReg.getScheduleWindow().get("start").asText()).isEqualTo("22:00");
        assertThat(dbReg.getDeployedAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(dbReg.getVersion()).isZero();
        assertThat(dbReg.getCreatedBy()).isEqualTo("system");

        AgentOversightLink dbLink = em.find(AgentOversightLink.class, link.getId());
        assertThat(dbLink.getPosition()).isEqualTo(1);
        assertThat(dbLink.getLabel()).isEqualTo("IT Oversight Lead");

        AgentStatusEvent dbEvent = em.find(AgentStatusEvent.class, event.getId());
        assertThat(dbEvent.getEventType()).isEqualTo(AgentEventType.DEPLOYED);
        assertThat(dbEvent.getActorType()).isEqualTo(ContractActorType.HUMAN);
        assertThat(dbEvent.getPayload().get("criteriaDigest").asText()).isEqualTo("abc");
        assertThat(dbEvent.getOccurredAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));

        AgentIntervention dbIntervention = em.find(AgentIntervention.class, intervention.getId());
        assertThat(dbIntervention.getStatus()).isEqualTo(AgentInterventionStatus.OPEN);
        assertThat(dbIntervention.getTriggerKind()).isEqualTo(AgentInterventionTrigger.HUMAN);
        assertThat(dbIntervention.getResolution()).isNull();

        AgentComplianceReview dbReview = em.find(AgentComplianceReview.class, review.getId());
        assertThat(dbReview.getDecision()).isEqualTo(AgentReviewDecision.NEEDS_MORE_TIME);
        assertThat(dbReview.getConsumedByEventId()).isNull();

        AgentCriteriaGate dbGate = em.find(AgentCriteriaGate.class, gate.getId());
        assertThat(dbGate.getStanding()).isEqualTo(AgentGateStanding.UNEVALUATED);
        assertThat(dbGate.getPredicate().get("gte").asDouble()).isEqualTo(0.90);

        AgentMetricSample dbSample = em.find(AgentMetricSample.class, sample.getId());
        assertThat(dbSample.getMetricValue()).isEqualTo(0.93);
        assertThat(dbSample.getPeriodKey()).isEqualTo("2026-05");

        AgentThrottleBudget dbBudget = em.find(AgentThrottleBudget.class, budget.getId());
        assertThat(dbBudget.isActive()).isTrue();
        assertThat(dbBudget.getBudgetKind()).isEqualTo("ACTIONS_PER_WINDOW");

        AgentRateCounter dbCounter = em.find(AgentRateCounter.class, counter.getId());
        assertThat(dbCounter.getActionCount()).isEqualTo(3);

        AgentSighting dbSighting = em.find(AgentSighting.class, sighting.getId());
        assertThat(dbSighting.getChannel()).isEqualTo(AgentSightingChannel.HEARTBEAT);
        assertThat(dbSighting.getBucketKey()).isEqualTo("2026-06-01T10");

        AgentNotification dbNotification = em.find(AgentNotification.class, notification.getId());
        assertThat(dbNotification.getNotificationType()).isEqualTo(AgentNotificationType.AGENT_DEPLOYED);
        assertThat(dbNotification.getReadAt()).isNull();
        assertThat(dbNotification.getDispatchedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // 3. Repository queries — derived, JPQL, PESSIMISTIC_WRITE and the atomic
    //    counter increment on PG
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void agentRepositoryQueriesAndPessimisticLocksExecuteOnPostgres() {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = registrationRepository.save(newRegistration(domainId, "it-query-agent"));
        AgentRegistration retired = newRegistration(domainId, "it-retired-agent");
        retired.setOperationalMode(AgentOperationalMode.RETIRED);
        registrationRepository.save(retired);

        AgentStatusEvent event = new AgentStatusEvent();
        event.setAgentRegistrationId(reg.getId());
        event.setDomainId(domainId);
        event.setEventSeq(1);
        event.setEventType(AgentEventType.DEPLOYED);
        event.setActorType(ContractActorType.HUMAN);
        event.setActorLabel("IT Operator");
        event.setIdempotencyKey("it-q-1");
        event.setOccurredAt(Instant.parse("2026-06-01T00:00:00Z"));
        eventRepository.save(event);

        em.flush();
        em.clear();

        assertThat(registrationRepository.findByDomainId(domainId)).hasSize(2);
        assertThat(registrationRepository.findByDomainIdAndOperationalMode(
                domainId, AgentOperationalMode.SHADOW)).hasSize(1);
        // the default fleet exclusion lane: retired filtered out (spec §5.2 / ADR D4)
        assertThat(registrationRepository.findByDomainIdAndOperationalModeNot(
                domainId, AgentOperationalMode.RETIRED))
                .extracting(AgentRegistration::getAgentKey)
                .containsExactly("it-query-agent");
        assertThat(registrationRepository.findByDomainIdAndAgentKey(domainId, "it-query-agent")).isPresent();
        // SELECT … FOR UPDATE — the lifecycle serialization point (§5.5)
        assertThat(registrationRepository.findByIdForUpdate(reg.getId())).isPresent();

        assertThat(eventRepository.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId())).hasSize(1);
        assertThat(eventRepository.findByAgentRegistrationIdAndIdempotencyKey(reg.getId(), "it-q-1")).isPresent();
        assertThat(eventRepository.findFirstByAgentRegistrationIdOrderByEventSeqDesc(reg.getId())).isPresent();

        // atomic in-DB increment (S12 lesson): absent window → 0 rows; present → +1
        Instant window = Instant.parse("2026-06-01T10:00:00Z");
        assertThat(rateCounterRepository.incrementWindow(reg.getId(), window)).isZero();
        AgentRateCounter counter = new AgentRateCounter();
        counter.setAgentRegistrationId(reg.getId());
        counter.setWindowStart(window);
        rateCounterRepository.save(counter);
        em.flush();
        assertThat(rateCounterRepository.incrementWindow(reg.getId(), window)).isEqualTo(1);
        em.clear();
        assertThat(rateCounterRepository.findByAgentRegistrationIdAndWindowStart(reg.getId(), window))
                .isPresent()
                .get()
                .satisfies(c -> assertThat(c.getActionCount()).isEqualTo(1));
    }

    // -----------------------------------------------------------------------
    // 4. V6_1 append-only trigger: direct JDBC UPDATE/DELETE raises (the SQL
    //    half of test plan #6; the repo-surface half is ArchUnit-pinned in
    //    GovernedArchitectureTest)
    // -----------------------------------------------------------------------

    @Test
    void appendOnlyTriggerRaisesOnUpdateAndDelete() {
        UUID agentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertEventRow(eventId, agentId, 1, "it-trigger-1");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE agent_status_events SET actor_label = 'tampered' WHERE id = ?", eventId))
                .as("UPDATE on the audit log raises from trg_ase_append_only")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM agent_status_events WHERE id = ?", eventId))
                .as("DELETE on the audit log raises from trg_ase_append_only")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_status_events WHERE id = ?", Integer.class, eventId);
        assertThat(rows).as("the row survives both attempts untouched").isEqualTo(1);
    }

    /**
     * V6_2 (engine-spec agents.md §11): the ONE sanctioned mutation — the
     * chain mirror's {@code decision_log_hash} NULL→value write-back — passes
     * the amended trigger; re-writing the hash, or piggy-backing any audit
     * column on the same UPDATE, still raises.
     */
    @Test
    void appendOnlyTriggerPermitsExactlyTheMirrorHashWriteBack() {
        UUID agentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertEventRow(eventId, agentId, 1, "it-trigger-writeback-1");

        // the sanctioned transition: NULL -> hash, every audit column identical
        int updated = jdbcTemplate.update(
                "UPDATE agent_status_events SET decision_log_hash = ? WHERE id = ?",
                "9dc1a243b4ec944219f7c31a58970662dc7f0c99e82eb085fe711d46a2e1ed74", eventId);
        assertThat(updated).isEqualTo(1);

        // the hash is itself write-once
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE agent_status_events SET decision_log_hash = ? WHERE id = ?",
                "0000000000000000000000000000000000000000000000000000000000000000", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // piggy-backing audit content on the write-back shape still raises
        UUID second = UUID.randomUUID();
        insertEventRow(second, agentId, 2, "it-trigger-writeback-2");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE agent_status_events SET decision_log_hash = ?, actor_label = 'tampered'"
                        + " WHERE id = ?",
                "9dc1a243b4ec944219f7c31a58970662dc7f0c99e82eb085fe711d46a2e1ed74", second))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    // -----------------------------------------------------------------------
    // 5. V6_1 partial uniques fire; out-of-predicate rows are exempt
    // -----------------------------------------------------------------------

    @Test
    void openInterventionPartialUniqueDedupesPerSourceKey() {
        UUID agentId = UUID.randomUUID();
        UUID domainId = UUID.randomUUID();

        insertInterventionRow(agentId, domainId, "active-while-paused", "OPEN");
        assertThatThrownBy(() ->
                insertInterventionRow(agentId, domainId, "active-while-paused", "OPEN"))
                .as("second OPEN row per (agent, source_key) violates the I12 backstop")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_open_intervention_per_source");

        // NULL source keys COALESCE to '' — two engine-shaped NULL-source OPEN rows collide too
        UUID agent2 = UUID.randomUUID();
        insertInterventionRow(agent2, domainId, null, "OPEN");
        assertThatThrownBy(() -> insertInterventionRow(agent2, domainId, null, "OPEN"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_open_intervention_per_source");

        // RESOLVED rows sit outside the partial predicate — history accumulates legally
        insertInterventionRow(agentId, domainId, "active-while-paused", "RESOLVED");
        insertInterventionRow(agentId, domainId, "active-while-paused", "RESOLVED");
    }

    @Test
    void activeBudgetPartialUniqueAllowsOneActivePerKind() {
        UUID agentId = UUID.randomUUID();

        insertBudgetRow(agentId, "ACTIONS_PER_WINDOW", true);
        assertThatThrownBy(() -> insertBudgetRow(agentId, "ACTIONS_PER_WINDOW", true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_active_budget_per_kind");

        // inactive history rows are exempt; another kind is fine
        insertBudgetRow(agentId, "ACTIONS_PER_WINDOW", false);
        insertBudgetRow(agentId, "ACTIONS_PER_WINDOW", false);
        insertBudgetRow(agentId, "TOKENS_PER_DAY", true);
    }

    @Test
    void principalPartialUniqueFiresOnlyWhenPresent() {
        UUID domainId = UUID.randomUUID();
        insertRegistrationRow(domainId, "it-principal-a", "shared-principal");
        assertThatThrownBy(() -> insertRegistrationRow(domainId, "it-principal-b", "shared-principal"))
                .as("a bound principal resolves to exactly one registration (gateway correctness)")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_agent_principal");

        // NULL principals are unconstrained — unbound agents are legion
        insertRegistrationRow(domainId, "it-unbound-a", null);
        insertRegistrationRow(domainId, "it-unbound-b", null);
    }

    // -----------------------------------------------------------------------
    // 6. Plain unique keys + UNIQUE-with-NULL dedupe_key (trap #4)
    // -----------------------------------------------------------------------

    @Test
    void plainUniqueKeysAreBackstoppedByConstraints() {
        UUID domainId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        // uq_agent_domain_key — slug unique per domain (§5.4 AGENT_KEY_CONFLICT backstop)
        insertRegistrationRow(domainId, "it-dup-key", null);
        assertThatThrownBy(() -> insertRegistrationRow(domainId, "it-dup-key", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_agent_domain_key");

        // uq_ase_idem — idempotency replay backstop (cascade D8 idiom)
        insertEventRow(UUID.randomUUID(), agentId, 1, "it-idem-1");
        assertThatThrownBy(() -> insertEventRow(UUID.randomUUID(), agentId, 2, "it-idem-1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_ase_idem");

        // uq_ase_seq — gapless fencing backstop (invariant I3)
        assertThatThrownBy(() -> insertEventRow(UUID.randomUUID(), agentId, 1, "it-idem-2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_ase_seq");

        // uq_ams_sample — metric idempotent-upsert key
        insertMetricSampleRow(agentId, "adherence", "2026-05");
        assertThatThrownBy(() -> insertMetricSampleRow(agentId, "adherence", "2026-05"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_ams_sample");

        // uq_as_bucket — hourly sighting dedupe
        insertSightingRow(agentId, "HEARTBEAT", "2026-06-01T10");
        assertThatThrownBy(() -> insertSightingRow(agentId, "HEARTBEAT", "2026-06-01T10"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_as_bucket");

        // uq_arc_window — one counter row per window
        insertCounterRow(agentId, "2026-06-01 10:00:00+00");
        assertThatThrownBy(() -> insertCounterRow(agentId, "2026-06-01 10:00:00+00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_arc_window");
    }

    @Test
    void notificationDedupeKeyIsUniqueButNullsCoexist() {
        UUID domainId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        insertNotificationRow(domainId, agentId, "it-dedupe-1");
        assertThatThrownBy(() -> insertNotificationRow(domainId, agentId, "it-dedupe-1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_an_dedupe");

        // trap #4: NULL dedupe keys are legal and UNLIMITED — postgres UNIQUE ignores NULLs
        insertNotificationRow(domainId, agentId, null);
        insertNotificationRow(domainId, agentId, null);
        Integer nullRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_notifications WHERE agent_registration_id = ? AND dedupe_key IS NULL",
                Integer.class, agentId);
        assertThat(nullRows).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static AgentRegistration newRegistration(UUID domainId, String agentKey) {
        AgentRegistration reg = new AgentRegistration();
        reg.setDomainId(domainId);
        reg.setAgentKey(agentKey);
        reg.setName("IT Agent " + agentKey);
        reg.setRoleDescription("Synthetic IT role");
        reg.setExecutionLocus(AgentExecutionLocus.CUSTOMER_SYSTEM);
        reg.setDeployedByLabel("IT Deploy Team");
        reg.setDeployedAt(Instant.parse("2026-06-01T00:00:00Z"));
        reg.setDeploymentCriteria("Synthetic IT mandate, target 90% accuracy");
        reg.setCriteriaDigest("f".repeat(64));
        reg.setSupervisoryModel(AgentSupervisoryModel.HUMAN_IN_LOOP);
        reg.setOperationalMode(AgentOperationalMode.SHADOW);
        reg.setShadowEnteredAt(Instant.parse("2026-06-01T00:00:00Z"));
        reg.setEventSeq(1);
        return reg;
    }

    private void insertRegistrationRow(UUID domainId, String agentKey, String principal) {
        jdbcTemplate.update(
                "INSERT INTO agent_registrations (id, domain_id, agent_key, name, role_description, "
                        + "execution_locus, mcp_principal_id, deployed_by_label, deployed_at, "
                        + "deployment_criteria, criteria_digest, supervisory_model, operational_mode, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 'IT raw agent', 'IT raw role', 'CUSTOMER_SYSTEM', ?, "
                        + "'agent-it', CURRENT_TIMESTAMP, 'IT raw mandate', 'beef', 'HUMAN_IN_LOOP', "
                        + "'SHADOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), domainId, agentKey, principal);
    }

    private void insertEventRow(UUID id, UUID agentId, int eventSeq, String idempotencyKey) {
        jdbcTemplate.update(
                "INSERT INTO agent_status_events (id, agent_registration_id, domain_id, event_seq, "
                        + "event_type, actor_type, actor_label, idempotency_key, occurred_at, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, 'DEPLOYED', 'HUMAN', 'agent-it', ?, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                id, agentId, UUID.randomUUID(), eventSeq, idempotencyKey);
    }

    private void insertInterventionRow(UUID agentId, UUID domainId, String sourceKey, String status) {
        jdbcTemplate.update(
                "INSERT INTO agent_interventions (id, agent_registration_id, domain_id, status, "
                        + "trigger_kind, source_key, reason, opened_by_label, opened_at, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, 'GATEWAY_VIOLATION', ?, 'IT zombie signal', 'engine', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), agentId, domainId, status, sourceKey);
    }

    private void insertBudgetRow(UUID agentId, String kind, boolean active) {
        jdbcTemplate.update(
                "INSERT INTO agent_throttle_budgets (id, agent_registration_id, budget_kind, "
                        + "limit_value, window_minutes, active, set_by_label, set_at, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 100, 60, ?, 'agent-it', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), agentId, kind, active);
    }

    private void insertMetricSampleRow(UUID agentId, String metricKey, String periodKey) {
        jdbcTemplate.update(
                "INSERT INTO agent_metric_samples (id, agent_registration_id, metric_key, metric_value, "
                        + "period_key, reported_at, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 0.9, ?, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), agentId, metricKey, periodKey);
    }

    private void insertSightingRow(UUID agentId, String channel, String bucketKey) {
        jdbcTemplate.update(
                "INSERT INTO agent_sightings (id, agent_registration_id, seen_at, channel, bucket_key, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), agentId, channel, bucketKey);
    }

    private void insertCounterRow(UUID agentId, String windowStart) {
        jdbcTemplate.update(
                "INSERT INTO agent_rate_counters (id, agent_registration_id, window_start, action_count, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?::timestamptz, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), agentId, windowStart);
    }

    private void insertNotificationRow(UUID domainId, UUID agentId, String dedupeKey) {
        jdbcTemplate.update(
                "INSERT INTO agent_notifications (id, domain_id, agent_registration_id, notification_type, "
                        + "recipient_label, tone, dedupe_key, created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, 'AGENT_DEPLOYED', 'IT Oversight Lead', 'info', ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'agent-it', 0)",
                UUID.randomUUID(), domainId, agentId, dedupeKey);
    }

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad test JSON literal: " + content, e);
        }
    }
}
