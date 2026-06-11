package com.meridian.substrate.engine.contract.monitor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.2 next-cadence-boundary table (engine-spec contracts.md §5.2 step 2;
 * jitter is the caller's, so these are exact).
 */
class NextRunTimesTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    @Test
    void monthlyAdvancesToFirstOfNextMonth() {
        assertThat(NextRunTimes.nextBoundary("MONTHLY", Instant.parse("2026-05-09T13:45:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void monthlyOnTheBoundaryInstantIsStrictlyAfter() {
        assertThat(NextRunTimes.nextBoundary("MONTHLY", Instant.parse("2026-06-01T00:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void quarterlyAdvancesToFirstOfNextQuarter() {
        assertThat(NextRunTimes.nextBoundary("QUARTERLY", Instant.parse("2026-05-09T00:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(NextRunTimes.nextBoundary("QUARTERLY", Instant.parse("2026-03-31T23:59:59Z"), UTC))
                .isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(NextRunTimes.nextBoundary("quarterly", Instant.parse("2026-12-15T00:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void weeklyAdvancesToNextIsoMonday() {
        // 2026-05-09 is a Saturday; next Monday is 2026-05-11.
        assertThat(NextRunTimes.nextBoundary("WEEKLY", Instant.parse("2026-05-09T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-05-11T00:00:00Z"));
        // From a Monday, "next Monday" is strictly the following week.
        assertThat(NextRunTimes.nextBoundary("WEEKLY", Instant.parse("2026-05-11T00:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-05-18T00:00:00Z"));
    }

    @Test
    void dailyAndUnknownCadencesAdvanceToNextMidnight() {
        assertThat(NextRunTimes.nextBoundary("DAILY", Instant.parse("2026-05-09T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
        // Defensive fallback (never-silent posture): unknown cadence sweeps daily.
        assertThat(NextRunTimes.nextBoundary("FORTNIGHTLY", Instant.parse("2026-05-09T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
        assertThat(NextRunTimes.nextBoundary(null, Instant.parse("2026-05-09T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
    }

    @Test
    void boundariesAreComputedInTheConfiguredZone() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        // 2026-05-31T16:00Z is already 2026-06-01T01:00 in Tokyo — next month
        // there is July, starting at 2026-06-30T15:00Z.
        assertThat(NextRunTimes.nextBoundary("MONTHLY", Instant.parse("2026-05-31T16:00:00Z"), tokyo))
                .isEqualTo(Instant.parse("2026-06-30T15:00:00Z"));
    }
}
