package com.meridian.substrate.app.postgres;

import com.meridian.substrate.app.cascade.CascadeApiTestSupport;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.cascade.CascadeEscalationSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §12 test #8 Postgres gate (engine-spec cascade.md §9 over real Postgres 16):
 * sweeper idempotency with the REAL {@code uq_cn_dedupe} UNIQUE backstop and
 * the real per-cascade {@code SELECT … FOR UPDATE} — sweep twice past the SLA
 * deadline → exactly one {@code LEVEL_OVERDUE} per recipient; overdue
 * projection flags set; and the ADR D5 non-negotiable: NO state transition.
 *
 * <p>The context pins a stepping UTC clock ({@code @Primary} over the
 * engine's {@code @ConditionalOnMissingBean} system clock — fixture seam S15)
 * so deadlines elapse deterministically. Deliberately NOT
 * {@code @Transactional}: the sweeper commits per cascade by design, so the
 * seeded chain commits through the real verb path first.
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresCascadeEscalationSweeperIT extends CascadeApiTestSupport {

    private static final Instant T0 = Instant.parse("2026-04-08T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    CascadeEscalationSweeper sweeper;
    @Autowired
    CascadeRepository cascadeRepository;
    @Autowired
    CascadeLevelRepository levelRepository;
    @Autowired
    CascadeNotificationRepository notificationRepository;
    @Autowired
    SteppingClock clock;

    @Test
    void sweepIsIdempotentPerRecipientAndNeverTransitionsStateOnPostgres() {
        clock.set(T0);
        // Real verb path, committed (no outer test tx): issue → L1 cascade →
        // frontier L2 LOOKING activated at T0, SLA default 7d.
        Cascade c = issue(openDraft(createDomain("Holding Co"), null, null), null, L1_OWNER, null);
        cascadeLevel(c.getId(), 1, L1_OWNER, L2_OWNER, null);
        UUID id = c.getId();

        // Before the deadline: nothing.
        clock.set(T0.plus(Duration.ofDays(6)));
        sweeper.sweep();
        assertThat(rows(id)).isEmpty();

        // Past the deadline, swept twice: exactly one row per §8 recipient
        // (frontier owner + level-above owner + originator) — the dedupe key
        // check plus the real Postgres UNIQUE backstop.
        clock.set(T0.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
        sweeper.sweep();
        sweeper.sweep();
        List<CascadeNotification> rows = rows(id);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(CascadeNotification::getRecipientLabel)
                .containsExactlyInAnyOrder(L2_OWNER, L1_OWNER, ORIGINATOR);
        assertThat(rows).extracting(CascadeNotification::getDedupeKey)
                .allMatch(key -> key.startsWith("overdue:" + id + ":2:1:0:"));

        // Projection overlay set; state untouched (notification-only, ADR D5).
        Cascade swept = cascadeRepository.findById(id).orElseThrow();
        assertThat(swept.isOverdue()).isTrue();
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 2).orElseThrow().isOverdue()).isTrue();
        assertThat(swept.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
        assertThat(swept.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
        assertThat(swept.getChainSeq()).isEqualTo(2);

        // The level finally acts: the lifecycle path clears the flags (§9).
        decline(id, 2, L2_OWNER);
        Cascade acted = cascadeRepository.findById(id).orElseThrow();
        assertThat(acted.isOverdue()).isFalse();
        assertThat(levelRepository.findByCascadeIdAndDepth(id, 2).orElseThrow().isOverdue()).isFalse();
    }

    private List<CascadeNotification> rows(UUID cascadeId) {
        return notificationRepository.findByCascadeIdAndNotificationType(
                cascadeId, CascadeNotificationType.LEVEL_OVERDUE);
    }

    // ------------------------------------------------------------ fixed clock

    @TestConfiguration
    static class SteppingClockConfig {

        @Bean
        @Primary
        SteppingClock steppingClock() {
            return new SteppingClock(T0);
        }
    }

    /** Stepping fixed clock (fixture seam S15) — UTC-pinned, stepped by the test. */
    static class SteppingClock extends Clock {

        private volatile Instant current;

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
            throw new UnsupportedOperationException("sweeper IT clock is UTC-pinned");
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
