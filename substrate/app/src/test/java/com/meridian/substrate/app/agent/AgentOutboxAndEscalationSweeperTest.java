package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentNotification;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentNotificationRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.AgentEscalationSweeper;
import com.meridian.substrate.engine.agent.AgentNotificationService;
import com.meridian.substrate.engine.agent.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#14 — outbox + sweeper</b> (engine-spec agents.md §13.4;
 * ADR-003 D13; ADR-001 D10): outbox rows land in the SAME transaction as the
 * triggering event; the §13.4 fan-out table holds (INTERVENTION_OPENED → full
 * chain; CONTROL_REQUESTED → position 1); escalation windows fire EXACTLY
 * ONCE per deterministic dedupe key under repeated sweeps; the renotify limit
 * bounds the window walk; and the sweeper NEVER transitions state. The
 * companion architecture assertion (no {@code @TransactionalEventListener} in
 * {@code engine.agent}) lives in {@code AgentEngineArchitectureTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentOutboxAndEscalationSweeperTest extends AgentVerbTestSupport {

    @Autowired AgentNotificationRepository notifications;
    @Autowired AgentInterventionRepository interventions;
    @Autowired AgentOversightLinkRepository oversightLinks;
    @Autowired AgentStatusEventRepository events;
    @Autowired AgentProperties properties;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ObjectMapper mapper;
    @Autowired com.meridian.substrate.common.repo.AgentRegistrationRepository registrationRepo;

    private AgentCriteriaGateEvaluationTest.MutableClock clock;
    private AgentEscalationSweeper sweeper;

    @BeforeEach
    void buildFixedClockSweeper() {
        clock = new AgentCriteriaGateEvaluationTest.MutableClock(Instant.now());
        sweeper = new AgentEscalationSweeper(registrationRepo, interventions, oversightLinks,
                events, new AgentNotificationService(notifications), properties, clock,
                mapper, txManager);
    }

    // -------------------------------------------------------- same-tx fan-out

    @Test
    void verbOutboxRowsAreVisibleInTheSameTransactionWithTheSpecFanOut() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-noshow");

        // INTERVENTION_OPENED → FULL oversight chain, destructive tone
        postVerb("a-noshow/interventions",
                reasonedBody("Drift detected — false-positive rate up 9 pts"))
                .andExpect(status().isCreated());
        List<AgentNotification> opened = notifications.findByAgentRegistrationIdAndNotificationType(
                reg.getId(), AgentNotificationType.INTERVENTION_OPENED);
        assertThat(opened).hasSize(2);                       // both chain positions
        assertThat(opened).extracting(AgentNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(POSITION_1, POSITION_2);
        assertThat(opened).allSatisfy(row -> {
            assertThat(row.getTone()).isEqualTo("destructive");
            assertThat(row.getEventId()).isNotNull();        // same-tx triggering event ref
        });

        // CONTROL_REQUESTED (recorded-intent suspension) → position 1 ONLY, warn tone
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending Compliance review"))
                .andExpect(status().isOk());
        List<AgentNotification> requested = notifications.findByAgentRegistrationIdAndNotificationType(
                reg.getId(), AgentNotificationType.CONTROL_REQUESTED);
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0).getRecipientLabel()).isEqualTo(POSITION_1);
        assertThat(requested.get(0).getTone()).isEqualTo("warn");
    }

    // ----------------------------------------------------- escalation sweeper

    @Test
    void interventionDwellEscalatesToPositionTwoExactlyOncePerWindow() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-noshow");
        postVerb("a-noshow/interventions",
                reasonedBody("Drift detected — false-positive rate up 9 pts"))
                .andExpect(status().isCreated());
        Instant openedAt = interventions.findByAgentRegistrationIdOrderByOpenedAtDesc(reg.getId())
                .get(0).getOpenedAt();

        // before the dwell: nothing escalates
        clock.set(openedAt.plus(Duration.ofDays(properties.getInterventionEscalationDays()))
                .minusSeconds(60));
        sweeper.sweepOnce();
        assertThat(escalated(reg)).isEmpty();

        // dwell elapsed: window n=0 → position 2, EXACTLY once across repeated sweeps
        clock.set(openedAt.plus(Duration.ofDays(properties.getInterventionEscalationDays()))
                .plusSeconds(3600));
        sweeper.sweepOnce();
        sweeper.sweepOnce();
        List<AgentNotification> first = escalated(reg);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getRecipientLabel()).isEqualTo(POSITION_2);
        assertThat(first.get(0).getTone()).isEqualTo("destructive");

        // a renotify window later: one MORE row (n=1), clamped at the chain tail
        clock.set(openedAt.plus(Duration.ofDays(properties.getInterventionEscalationDays()
                + properties.getEscalationRenotifyDays())).plusSeconds(3600));
        sweeper.sweepOnce();
        assertThat(escalated(reg)).hasSize(2);

        // far past every window: bounded by the renotify limit (n = 0..limit)
        clock.set(openedAt.plus(Duration.ofDays(365)));
        sweeper.sweepOnce();
        sweeper.sweepOnce();
        assertThat(escalated(reg)).hasSize(properties.getEscalationRenotifyLimit() + 1);

        // notification-ONLY: the sweeper never moved agent state
        AgentRegistration after = registrationRepo.findById(reg.getId()).orElseThrow();
        assertThat(after.getOperationalMode()).isEqualTo(AgentOperationalMode.SHADOW);
        assertThat(after.getOpenInterventionCount()).isEqualTo(1);
    }

    @Test
    void unconfirmedControlDwellNotifiesTheFullChainAndStopsOnConfirmation() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending Compliance review"))
                .andExpect(status().isOk());
        Instant requestedAt = registrationRepo.findById(reg.getId()).orElseThrow()
                .getControlRequestedAt();

        // before the timeout: silent
        clock.set(requestedAt.plus(Duration.ofHours(properties.getControlConfirmTimeoutHours()))
                .minusSeconds(60));
        sweeper.sweepOnce();
        assertThat(unconfirmed(reg)).isEmpty();

        // timeout elapsed: CONTROL_UNCONFIRMED to the FULL chain, exactly once per window
        clock.set(requestedAt.plus(Duration.ofHours(properties.getControlConfirmTimeoutHours()))
                .plusSeconds(3600));
        sweeper.sweepOnce();
        sweeper.sweepOnce();
        List<AgentNotification> rows = unconfirmed(reg);
        assertThat(rows).hasSize(2);                         // both chain positions, one window
        assertThat(rows).extracting(AgentNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(POSITION_1, POSITION_2);

        // human confirmation closes the dwell: later windows never fire
        ObjectNode confirm = verbBody();
        confirm.set("evidenceRefs", json.readTree("[\"doc:shutdown-evidence\"]"));
        postVerb(reg.getId() + "/confirm-control", confirm).andExpect(status().isOk());
        clock.set(requestedAt.plus(Duration.ofDays(30)));
        sweeper.sweepOnce();
        assertThat(unconfirmed(reg)).hasSize(2);             // unchanged
    }

    private List<AgentNotification> escalated(AgentRegistration reg) {
        return notifications.findByAgentRegistrationIdAndNotificationType(
                reg.getId(), AgentNotificationType.INTERVENTION_ESCALATED);
    }

    private List<AgentNotification> unconfirmed(AgentRegistration reg) {
        return notifications.findByAgentRegistrationIdAndNotificationType(
                reg.getId(), AgentNotificationType.CONTROL_UNCONFIRMED);
    }
}
