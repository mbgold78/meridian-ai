package com.meridian.substrate.app.core;

import com.meridian.substrate.app.config.StatsProperties;
import com.meridian.substrate.app.core.service.PortfolioReadService;
import com.meridian.substrate.app.core.service.read.InitiativeView;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.TransformationInitiative;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.InitiativeStatus;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
import com.meridian.substrate.engine.contract.registry.ContractSignalService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5 stats units, FIXED clock (engine-spec diagnostic.md §12.1/§12.4/§12.5;
 * test plan #14) — the pure §12 formulas, no Spring, no DB:
 *
 * <ul>
 *   <li>stall matrix: BLOCKED counts; IN_PROGRESS dwell over/under threshold;
 *       updatedAt movement resets; PLUG_IN never current; COMPLETE-only not
 *       stalled; zero-row domains never stalled (they are the separate
 *       {@code unphased} count);</li>
 *   <li>surfacedAt projection per §12.4;</li>
 *   <li>renderedProgressPct COMPLETE ⇒ 100 (§12.5 — the only computed rule);</li>
 *   <li>the §12.2 window-documentation pin: {@code substrate.stats.pattern-
 *       window-days} stays equal to the contracts-owned
 *       {@code COORDINATION_WINDOW} — divergence is a reviewed change, never
 *       silent drift.</li>
 * </ul>
 */
class PortfolioStatsUnitTest {

    /** Pinned "now" — every dwell below is computed against this instant. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = FIXED.instant();
    private static final Duration THRESHOLD = Duration.ofDays(90);

    private static PhaseState row(LifecyclePhase phase, PhaseStatus status,
                                  Instant enteredAt, Instant updatedAt) {
        PhaseState ps = new PhaseState();
        ps.setPhase(phase);
        ps.setStatus(status);
        ps.setEnteredAt(enteredAt);
        ps.setUpdatedAt(updatedAt);
        return ps;
    }

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Nested
    class StallMatrix {

        @Test
        void blockedCurrentPhaseIsStalledRegardlessOfDwell() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.MAP, PhaseStatus.BLOCKED, daysAgo(1), daysAgo(1)));
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isTrue();
        }

        @Test
        void inProgressOverThresholdIsStalled() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.DESIGN, PhaseStatus.IN_PROGRESS, daysAgo(91), daysAgo(91)));
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isTrue();
        }

        @Test
        void inProgressUnderThresholdIsNotStalled() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.DESIGN, PhaseStatus.IN_PROGRESS, daysAgo(89), daysAgo(89)));
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isFalse();
        }

        @Test
        void exactlyAtThresholdIsNotStalled_strictGreaterThan() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.DESIGN, PhaseStatus.IN_PROGRESS, daysAgo(90), daysAgo(90)));
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isFalse();
        }

        @Test
        void updatedAtMovementResetsTheDwell() {
            // Entered 200 days ago, but a checkpoint/blocker edit 3 days ago
            // counts as movement (§12.1: max(enteredAt, updatedAt)).
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.EXECUTE, PhaseStatus.IN_PROGRESS, daysAgo(200), daysAgo(3)));
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isFalse();
        }

        @Test
        void plugInIsNeverTheCurrentPhase() {
            // PLUG_IN is continuous and never exits — even ancient and
            // IN_PROGRESS it defines no current phase and no stall.
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.PLUG_IN, PhaseStatus.IN_PROGRESS, daysAgo(400), daysAgo(400)));
            assertThat(PortfolioReadService.currentPhase(rows)).isEmpty();
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isFalse();
        }

        @Test
        void completeOnlyDomainIsNotStalled() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.DESIGN, PhaseStatus.COMPLETE, daysAgo(300), daysAgo(300)),
                    row(LifecyclePhase.MAP, PhaseStatus.COMPLETE, daysAgo(200), daysAgo(200)));
            assertThat(PortfolioReadService.currentPhase(rows)).isEmpty();
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isFalse();
        }

        @Test
        void zeroRowsIsNeverStalled_unphasedIsASeparateCount() {
            assertThat(PortfolioReadService.isStalled(List.of(), NOW, THRESHOLD)).isFalse();
            assertThat(PortfolioReadService.currentPhase(List.of())).isEmpty();
        }

        @Test
        void currentPhaseIsTheHighestSequencedActiveRow() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.DESIGN, PhaseStatus.COMPLETE, daysAgo(300), daysAgo(300)),
                    row(LifecyclePhase.MAP, PhaseStatus.IN_PROGRESS, daysAgo(10), daysAgo(10)),
                    row(LifecyclePhase.EXECUTE, PhaseStatus.BLOCKED, daysAgo(5), daysAgo(5)));
            assertThat(PortfolioReadService.currentPhase(rows))
                    .get().extracting(PhaseState::getPhase).isEqualTo(LifecyclePhase.EXECUTE);
            // ...and BLOCKED there means stalled, even though MAP moved recently.
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isTrue();
        }

        @Test
        void nullEnteredAtFallsBackToUpdatedAt() {
            List<PhaseState> rows = List.of(
                    row(LifecyclePhase.PLAN, PhaseStatus.IN_PROGRESS, null, daysAgo(120)));
            assertThat(PortfolioReadService.isStalled(rows, NOW, THRESHOLD)).isTrue();
        }
    }

    @Nested
    class SurfacedAtProjection {

        private Finding finding(CurationStatus status, Instant createdAt, Instant curatedAt) {
            Finding f = new Finding();
            f.setCurationStatus(status);
            f.setCreatedAt(createdAt);
            f.setCuratedAt(curatedAt);
            return f;
        }

        @Test
        void nullCurationStatusSurfacesAtCreatedAt() {
            Instant created = daysAgo(30);
            assertThat(PortfolioReadService.surfacedAt(finding(null, created, null)))
                    .isEqualTo(created);
        }

        @Test
        void acceptedSurfacesAtTheAcceptInstant() {
            Instant created = daysAgo(30);
            Instant accepted = daysAgo(2);
            assertThat(PortfolioReadService.surfacedAt(
                    finding(CurationStatus.ACCEPTED, created, accepted)))
                    .isEqualTo(accepted);
        }

        @Test
        void pendingRejectedSupersededAreNotClientSurfaced() {
            for (CurationStatus status : List.of(CurationStatus.PENDING_REVIEW,
                    CurationStatus.REJECTED, CurationStatus.SUPERSEDED)) {
                Finding f = finding(status, daysAgo(30), daysAgo(1));
                assertThat(PortfolioReadService.surfacedAt(f)).as("%s", status).isNull();
                assertThat(PortfolioReadService.clientSurfaced(f)).as("%s", status).isFalse();
            }
        }
    }

    @Nested
    class ProgressPctRule {

        private TransformationInitiative initiative(InitiativeStatus status, int pct) {
            TransformationInitiative i = new TransformationInitiative();
            i.setStatus(status);
            i.setProgressPct(pct);
            return i;
        }

        @Test
        void completeRendersOneHundredRegardlessOfStoredValue() {
            assertThat(InitiativeView.renderedProgressPct(
                    initiative(InitiativeStatus.COMPLETE, 40))).isEqualTo(100);
        }

        @Test
        void everyOtherStatusRendersTheOperatorValueVerbatim() {
            for (InitiativeStatus status : List.of(InitiativeStatus.PLANNED,
                    InitiativeStatus.IN_PROGRESS, InitiativeStatus.DEFERRED,
                    InitiativeStatus.CANCELLED)) {
                assertThat(InitiativeView.renderedProgressPct(initiative(status, 40)))
                        .as("%s", status).isEqualTo(40);
            }
        }
    }

    /**
     * §12.2 ownership pin: {@code substrate.stats.pattern-window-days} is
     * DOCUMENTATION of the contracts-owned window, never a fork. If the
     * contract engine recalibrates its window, this fails and the stats key
     * must be updated in the same change.
     */
    @Test
    void patternWindowDocumentationMatchesTheOwningEngine() {
        assertThat(new StatsProperties().getPatternWindowDays())
                .isEqualTo((int) ContractSignalService.COORDINATION_WINDOW.toDays());
    }
}
