package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L1_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.L2_OWNER;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.ORIGINATOR;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.T0;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.cascadeCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.declineCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.issueCommand;
import static com.meridian.substrate.engine.cascade.CascadeEngineTestSupport.openDraft;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test plan #8 (engine-spec cascade.md §12) — sweeper idempotency under a
 * stepping fixed clock (fixture seam S15): SLA 7d, past deadline → sweep twice
 * → exactly one {@code LEVEL_OVERDUE} per recipient (deterministic dedupe key
 * + UNIQUE backstop); one renotify window later → a second notification per
 * recipient; past the window limit → no further rows; a level action clears
 * the {@code overdue} flags; {@code responseSlaDays=0} → never swept. Plus the
 * decline-pending dwell lane ({@code DECLINE_OVERDUE}) and the ADR D5
 * non-negotiable: the sweeper NEVER transitions state.
 *
 * <p>Same slice mechanics as {@link CascadeNotificationOutboxTest}: services
 * hand-constructed over the shared repositories, verbs and sweeps committing
 * through explicit transactions (the sweeper commits per cascade by design).
 * H2 PG-mode lane; Testcontainers-Postgres in CI is the acceptance gate.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cascade-sweeper;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CascadeEscalationSweeperTest {

    @Autowired
    private CascadeRepository cascadeRepository;
    @Autowired
    private CascadeLevelRepository levelRepository;
    @Autowired
    private CascadeLevelActionRepository actionRepository;
    @Autowired
    private CascadeDraftRepository draftRepository;
    @Autowired
    private CascadeNotificationRepository notificationRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    private final SteppingClock clock = new SteppingClock(T0);
    private CascadeLifecycleService lifecycle;
    private CascadeEscalationSweeper sweeper;
    private TransactionTemplate tx;
    private int keySeq;

    @BeforeEach
    void setUp() {
        CascadeProperties properties = CascadeEngineTestSupport.defaultProperties();
        lifecycle = CascadeEngineTestSupport.lifecycleService(cascadeRepository, levelRepository,
                actionRepository, draftRepository, notificationRepository,
                (cascade, action) -> { /* no-op hook */ }, properties, clock);
        sweeper = new CascadeEscalationSweeper(cascadeRepository, levelRepository,
                actionRepository, new CascadeNotificationService(notificationRepository, clock),
                properties, clock, new ObjectMapper(), txManager);
        tx = new TransactionTemplate(txManager);
        keySeq = 0;
        clock.set(T0);
    }

    // -------------------------------------------------- LOOKING overdue lane

    @Test
    void sweepTwicePastDeadline_yieldsExactlyOneLevelOverduePerRecipient() {
        UUID id = issuedAndCascaded(); // frontier L2 LOOKING, activated T0, SLA default 7d

        clock.set(T0.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
        sweeper.sweep();
        sweeper.sweep(); // double-fire: dedupe keys + UNIQUE backstop make it a no-op

        List<CascadeNotification> rows = rows(id, CascadeNotificationType.LEVEL_OVERDUE);
        // §8 fan-out: frontier owner + level-above owner + originator — once each.
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(CascadeNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(L2_OWNER, L1_OWNER, ORIGINATOR);
        assertThat(rows).extracting(CascadeNotification::getDedupeKey)
                .allMatch(key -> key.startsWith("overdue:" + id + ":2:1:0:"));
        // action_id stays NULL for sweeper rows (spec §3.5).
        assertThat(rows).extracting(CascadeNotification::getActionId).containsOnlyNulls();

        // Projection overlay set on the level + cascade rows (§9).
        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isTrue();
        assertThat(level(id, 2).isOverdue()).isTrue();
    }

    @Test
    void renotifyWindowsAccrue_andStopAtTheLimit() {
        UUID id = issuedAndCascaded();
        Instant deadline = T0.plus(Duration.ofDays(7));

        // First window elapsed (n=0).
        clock.set(deadline.plus(Duration.ofHours(1)));
        sweeper.sweep();
        assertThat(rows(id, CascadeNotificationType.LEVEL_OVERDUE)).hasSize(3);

        // One renotify window later (n=1, +3d): one more notification per recipient.
        clock.set(deadline.plus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
        sweeper.sweep();
        assertThat(rows(id, CascadeNotificationType.LEVEL_OVERDUE)).hasSize(6);

        // Far past the limit: windows n=0..5 exist and NOTHING beyond (§9 n = 0..limit).
        clock.set(deadline.plus(Duration.ofDays(365)));
        sweeper.sweep();
        sweeper.sweep();
        List<CascadeNotification> rows = rows(id, CascadeNotificationType.LEVEL_OVERDUE);
        assertThat(rows).hasSize(18); // 6 windows × 3 recipients
        assertThat(rows).extracting(CascadeNotification::getDedupeKey)
                .noneMatch(key -> key.contains(":6:") || key.startsWith("overdue:" + id + ":2:1:6"));
    }

    @Test
    void sweeperNeverTransitionsState() {
        UUID id = issuedAndCascaded();
        clock.set(T0.plus(Duration.ofDays(30)));

        sweeper.sweep();

        Cascade swept = cascadeRepository.findById(id).orElseThrow();
        // ADR D5: notification-only — the chain is exactly where the humans left it.
        assertThat(swept.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(swept.getFrontierDepth()).isEqualTo(2);
        assertThat(swept.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(swept.getChainSeq()).isEqualTo(2);
        assertThat(actionRepository.findByCascadeIdOrderByChainSeqAsc(id))
                .as("the sweeper appends no action rows")
                .hasSize(2);
    }

    @Test
    void levelActionClearsOverdueFlags() {
        UUID id = issuedAndCascaded();
        clock.set(T0.plus(Duration.ofDays(8)));
        sweeper.sweep();
        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isTrue();
        assertThat(level(id, 2).isOverdue()).isTrue();

        // The overdue level finally acts (a legitimate decline): the lifecycle
        // path clears the flags (§9) — never the sweeper.
        inTx(() -> lifecycle.decline(id, 2, declineCommand(key(), L2_OWNER)));

        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isFalse();
        assertThat(level(id, 2).isOverdue()).isFalse();
    }

    @Test
    void slaZeroCascadeIsNeverSwept() {
        CascadeDraft draft = openDraft(UUID.randomUUID());
        draft.setResponseSlaDays(0); // explicit 0 disables the SLA (ADR D5)
        UUID draftId = inTx(() -> draftRepository.save(draft)).getId();
        UUID id = inTx(() -> lifecycle.issue(draftId, issueCommand(key()))).getId();

        clock.set(T0.plus(Duration.ofDays(365)));
        sweeper.sweep();

        assertThat(rows(id, CascadeNotificationType.LEVEL_OVERDUE)).isEmpty();
        assertThat(rows(id, CascadeNotificationType.DECLINE_OVERDUE)).isEmpty();
        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isFalse();
        assertThat(level(id, 1).getResponseDeadline()).isNull();
    }

    @Test
    void frontierDepth1_partyDeduplicatesAboveOwnerAgainstOriginator() {
        UUID id = issued(); // frontier L1 LOOKING; level-above owner IS the originator (L0)

        clock.set(T0.plus(Duration.ofDays(8)));
        sweeper.sweep();

        List<CascadeNotification> rows = rows(id, CascadeNotificationType.LEVEL_OVERDUE);
        // frontier owner + (above == originator, deduplicated to one row).
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CascadeNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(L1_OWNER, ORIGINATOR);
    }

    // ----------------------------------------------- decline-pending dwell lane

    @Test
    void declineDwellPastSla_yieldsExactlyOneDeclineOverduePerRecipient() {
        UUID id = issuedAndCascaded();
        Instant declinedAt = T0.plus(Duration.ofDays(2));
        clock.set(declinedAt);
        inTx(() -> lifecycle.decline(id, 2, declineCommand(key(), L2_OWNER)));
        UUID declineActionId = latestDeclineActionId(id);

        // Dwell not yet elapsed: nothing.
        clock.set(declinedAt.plus(Duration.ofDays(6)));
        sweeper.sweep();
        assertThat(rows(id, CascadeNotificationType.DECLINE_OVERDUE)).isEmpty();

        // Past the SLA dwell: originator + response owner (L1), once each — twice swept.
        clock.set(declinedAt.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
        sweeper.sweep();
        sweeper.sweep();
        List<CascadeNotification> rows = rows(id, CascadeNotificationType.DECLINE_OVERDUE);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CascadeNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(ORIGINATOR, L1_OWNER);
        assertThat(rows).extracting(CascadeNotification::getDedupeKey)
                .allMatch(key -> key.startsWith("decline-overdue:" + id + ":" + declineActionId + ":0:"));

        // One renotify window later: a second row per recipient.
        clock.set(declinedAt.plus(Duration.ofDays(10)).plus(Duration.ofHours(1)));
        sweeper.sweep();
        assertThat(rows(id, CascadeNotificationType.DECLINE_OVERDUE)).hasSize(4);

        // §10 rebuilder parity: overdue projection flags are LOOKING-past-deadline
        // only — the decline dwell emits notifications, never flags.
        assertThat(cascadeRepository.findById(id).orElseThrow().isOverdue()).isFalse();
        assertThat(level(id, 2).isOverdue()).isFalse();
        // And still notification-only: no state transition happened.
        assertThat(cascadeRepository.findById(id).orElseThrow().getFrontierState())
                .isEqualTo(CascadeLevelState.DECLINED);
    }

    // ------------------------------------------------------------------ helpers

    private UUID issued() {
        UUID draftId = inTx(() -> draftRepository.save(openDraft(UUID.randomUUID()))).getId();
        return inTx(() -> lifecycle.issue(draftId, issueCommand(key()))).getId();
    }

    private UUID issuedAndCascaded() {
        UUID id = issued();
        inTx(() -> lifecycle.cascade(id, 1, cascadeCommand(key(), L1_OWNER, L2_OWNER)));
        return id;
    }

    private CascadeLevel level(UUID cascadeId, int depth) {
        return levelRepository.findByCascadeIdAndDepth(cascadeId, depth).orElseThrow();
    }

    private List<CascadeNotification> rows(UUID cascadeId, CascadeNotificationType type) {
        return notificationRepository.findByCascadeIdAndNotificationType(cascadeId, type);
    }

    private UUID latestDeclineActionId(UUID cascadeId) {
        return actionRepository.findByCascadeIdOrderByChainSeqAsc(cascadeId).stream()
                .filter(a -> a.getAction() == CascadeActionType.DECLINE)
                .reduce((first, second) -> second)
                .map(CascadeLevelAction::getId)
                .orElseThrow();
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private String key() {
        return "key-" + UUID.randomUUID() + "-" + (++keySeq);
    }

    /** Stepping fixed clock (fixture seam S15) — UTC-pinned, re-set per scenario step. */
    private static final class SteppingClock extends Clock {

        private Instant current;

        SteppingClock(Instant start) {
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
            throw new UnsupportedOperationException("sweeper test clock is UTC-pinned");
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
