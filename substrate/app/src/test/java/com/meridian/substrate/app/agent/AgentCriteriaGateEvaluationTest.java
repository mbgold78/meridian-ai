package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentCriteriaGate;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.repo.AgentCriteriaGateRepository;
import com.meridian.substrate.common.repo.AgentMetricSampleRepository;
import com.meridian.substrate.common.repo.AgentNotificationRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.AgentCriteriaGateService;
import com.meridian.substrate.engine.agent.AgentMetricService;
import com.meridian.substrate.engine.agent.AgentNotificationService;
import com.meridian.substrate.engine.agent.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#11 — criteria gates</b> (engine-spec agents.md §9;
 * ADR-003 D9), fixed-clock and through the REAL evaluation service:
 * MET/AT_RISK flips event CRITERIA_STANDING_CHANGED exactly once per change;
 * <b>INDETERMINATE never marks AT_RISK</b> (missing/unresolvable samples
 * return the standing to UNEVALUATED — the needs-input state distinct from
 * green); AT_RISK signals dedupe per gate+period; UNSTRUCTURED gates emit
 * recurring HUMAN_VERIFY_DUE per cadence window; and evaluation is
 * SIGNAL-ONLY — the operational mode never moves (never auto-pause,
 * never auto-kill). TinyDsl REUSE itself is pinned by
 * {@code AgentEngineArchitectureTest} (no second evaluator in engine.agent).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentCriteriaGateEvaluationTest extends AgentVerbTestSupport {

    private static final Instant JUNE_15 = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired AgentCriteriaGateRepository gates;
    @Autowired AgentStatusEventRepository events;
    @Autowired AgentMetricSampleRepository samples;
    @Autowired AgentNotificationRepository notifications;
    @Autowired AgentOversightLinkRepository oversightLinks;
    @Autowired AgentMetricService metricService;
    @Autowired AgentProperties properties;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ObjectMapper mapper;
    @Autowired com.meridian.substrate.common.repo.AgentRegistrationRepository registrationRepo;

    private MutableClock clock;
    private AgentCriteriaGateService gateService;

    @BeforeEach
    void buildFixedClockService() {
        clock = new MutableClock(JUNE_15);
        gateService = new AgentCriteriaGateService(registrationRepo, gates, events, samples,
                oversightLinks, new AgentNotificationService(notifications), properties,
                clock, mapper, txManager);
    }

    @Test
    void structuredGateFlipsAreEventedOncePerChangeAndIndeterminateNeverMarksAtRisk()
            throws Exception {
        AgentRegistration reg = deployWithRouterGates(UUID.randomUUID());
        UUID gateId = structuredGate(reg).getId();

        // 1. No samples ⇒ INDETERMINATE ⇒ stays UNEVALUATED; nothing evented, nothing red.
        gateService.evaluateAgent(reg.getId());
        assertThat(structuredGate(reg).getStanding()).isEqualTo(AgentGateStanding.UNEVALUATED);
        assertThat(structuredGate(reg).getLastEvaluatedAt()).isEqualTo(JUNE_15);
        assertThat(standingChangeEvents(reg, gateId)).isEmpty();
        assertThat(atRiskNotifications(reg)).isEmpty();

        // 2. Failing sample in the CLOSED period 2026-05 ⇒ AT_RISK + ONE event + ONE signal.
        metricService.report(reg.getId(), "routing_accuracy", 0.80, "2026-05", null);
        gateService.evaluateAgent(reg.getId());
        assertThat(structuredGate(reg).getStanding()).isEqualTo(AgentGateStanding.AT_RISK);
        assertThat(standingChangeEvents(reg, gateId)).hasSize(1);
        assertThat(atRiskNotifications(reg)).hasSize(1);
        assertThat(atRiskNotifications(reg).get(0).getRecipientLabel()).isEqualTo(POSITION_1);

        // …and the agent KEEPS OPERATING: signal-only, never auto-pause (§9.2).
        assertThat(registrationRepo.findById(reg.getId()).orElseThrow().getOperationalMode())
                .isEqualTo(AgentOperationalMode.SHADOW);

        // 3. Re-evaluating an unchanged standing appends nothing (bounded) and
        //    the AT_RISK signal stays deduped per gate+period.
        gateService.evaluateAgent(reg.getId());
        assertThat(standingChangeEvents(reg, gateId)).hasSize(1);
        assertThat(atRiskNotifications(reg)).hasSize(1);

        // 4. The metric recovers (idempotent same-period upsert) ⇒ MET, second event.
        metricService.report(reg.getId(), "routing_accuracy", 0.97, "2026-05", null);
        clock.set(JUNE_15.plusSeconds(3600));
        gateService.evaluateAgent(reg.getId());
        assertThat(structuredGate(reg).getStanding()).isEqualTo(AgentGateStanding.MET);
        assertThat(standingChangeEvents(reg, gateId)).hasSize(2);

        // 5. Samples become unresolvable ⇒ INDETERMINATE returns UNEVALUATED —
        //    NEVER AT_RISK (contracts D10 hard invariant carried over).
        samples.deleteAll(samples.findByAgentRegistrationIdAndMetricKeyOrderByReportedAtDesc(
                reg.getId(), "routing_accuracy"));
        clock.set(JUNE_15.plusSeconds(7200));
        gateService.evaluateAgent(reg.getId());
        assertThat(structuredGate(reg).getStanding()).isEqualTo(AgentGateStanding.UNEVALUATED);
        assertThat(standingChangeEvents(reg, gateId)).hasSize(3);
        assertThat(atRiskNotifications(reg)).hasSize(1);     // no new red signal, ever
        assertThat(gates.findById(gateId).orElseThrow().getGateText())
                .isEqualTo("target 95% routing accuracy");   // content immutable (I2)
    }

    @Test
    void openPeriodSamplesAreIndeterminate_notGreenNotRed() throws Exception {
        AgentRegistration reg = deployWithRouterGates(UUID.randomUUID());
        // 2026-06 is the period CONTAINING the clock — open, not closed.
        metricService.report(reg.getId(), "routing_accuracy", 0.99, "2026-06", null);
        gateService.evaluateAgent(reg.getId());
        assertThat(structuredGate(reg).getStanding()).isEqualTo(AgentGateStanding.UNEVALUATED);
        assertThat(standingChangeEvents(reg, structuredGate(reg).getId())).isEmpty();
    }

    @Test
    void unstructuredGatesEmitRecurringHumanVerifyDuePerCadenceWindow() throws Exception {
        AgentRegistration reg = deployWithRouterGates(UUID.randomUUID());
        gateService.evaluateAgent(reg.getId());
        AgentCriteriaGate unstructured = unstructuredGate(reg);
        assertThat(unstructured.getStanding()).isEqualTo(AgentGateStanding.HUMAN_VERIFY_DUE);
        assertThat(humanVerifyNotifications(reg)).hasSize(1);

        // same QUARTERLY window ⇒ deduped, no spam
        gateService.evaluateAgent(reg.getId());
        assertThat(humanVerifyNotifications(reg)).hasSize(1);

        // next quarter ⇒ the recurrence fires again (weak but never silently inert)
        clock.set(Instant.parse("2026-10-02T12:00:00Z"));
        gateService.evaluateAgent(reg.getId());
        assertThat(humanVerifyNotifications(reg)).hasSize(2);
        // …while the standing-change event stayed bounded to the first flip
        assertThat(standingChangeEvents(reg, unstructured.getId())).hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures

    /** Curasync a-msg-router mandate shape: STRUCTURED routing accuracy + UNSTRUCTURED PHI. */
    private AgentRegistration deployWithRouterGates(UUID domainId) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", key());
        body.put("domainId", domainId.toString());
        body.put("agentKey", "a-msg-router");
        body.put("name", "Patient Message Router");
        body.put("roleDescription", "Routes patient messages to the right clinical queue");
        body.put("executionLocus", "customer-system");
        body.put("deployedByLabel", OPERATOR);
        body.put("deploymentCriteria",
                "Route patient messages with 95% accuracy and zero PHI leakage");
        var gateArray = body.putArray("criteriaGates");
        ObjectNode structured = gateArray.addObject();
        structured.put("gateText", "target 95% routing accuracy");
        structured.put("ruleType", "structured");
        structured.set("predicate", json.readTree("{\"metric\":\"routing_accuracy\",\"gte\":0.95}"));
        structured.put("cadence", "MONTHLY");
        ObjectNode unstructured = gateArray.addObject();
        unstructured.put("gateText", "zero PHI leakage to non-clinical staff");
        unstructured.put("ruleType", "unstructured");
        unstructured.put("cadence", "QUARTERLY");
        var chain = body.putArray("oversightChain");
        chain.addObject().put("label", POSITION_1);
        chain.addObject().put("label", POSITION_2);
        body.put("supervisoryModel", "human-in-loop");
        mvc.perform(post("/api/v1/agents")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
        return registrations.findByDomainIdAndAgentKey(domainId, "a-msg-router").orElseThrow();
    }

    private AgentCriteriaGate structuredGate(AgentRegistration reg) {
        return gates.findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId()).stream()
                .filter(g -> g.getRuleType() == AgentGateRuleType.STRUCTURED)
                .findFirst().orElseThrow();
    }

    private AgentCriteriaGate unstructuredGate(AgentRegistration reg) {
        return gates.findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId()).stream()
                .filter(g -> g.getRuleType() == AgentGateRuleType.UNSTRUCTURED)
                .findFirst().orElseThrow();
    }

    /** Standing-change events for ONE gate (the unstructured sibling flips independently). */
    private List<com.meridian.substrate.common.domain.AgentStatusEvent> standingChangeEvents(
            AgentRegistration reg, UUID gateId) {
        return events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId()).stream()
                .filter(e -> e.getEventType() == AgentEventType.CRITERIA_STANDING_CHANGED)
                .filter(e -> e.getPayload() != null
                        && gateId.toString().equals(e.getPayload().path("gateId").asText()))
                .toList();
    }

    private List<com.meridian.substrate.common.domain.AgentNotification> atRiskNotifications(
            AgentRegistration reg) {
        return notifications.findByAgentRegistrationIdAndNotificationType(
                reg.getId(), AgentNotificationType.CRITERIA_AT_RISK);
    }

    private List<com.meridian.substrate.common.domain.AgentNotification> humanVerifyNotifications(
            AgentRegistration reg) {
        return notifications.findByAgentRegistrationIdAndNotificationType(
                reg.getId(), AgentNotificationType.HUMAN_VERIFY_DUE);
    }

    /** Settable fixed clock (cascade seeder S15 idiom). */
    static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) {
            this.current = start;
        }

        void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed-clock test seam (S15)");
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
