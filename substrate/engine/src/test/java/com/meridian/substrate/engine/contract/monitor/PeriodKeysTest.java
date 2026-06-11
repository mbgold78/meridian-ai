package com.meridian.substrate.engine.contract.monitor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.1 period-key table tests (engine-spec contracts.md §14.3 "Period keys:
 * MONTHLY/QUARTERLY boundaries, zone config, grace arithmetic (fixed
 * Clock)") — grace arithmetic itself is 3b evaluator scope; this pins the key
 * computation, closure boundaries, and the zone sensitivity the evaluators
 * and the obligations read model share.
 */
class PeriodKeysTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void monthlyKeys() {
        assertThat(PeriodKeys.periodKey("MONTHLY", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isEqualTo("2026-06");
        assertThat(PeriodKeys.previousPeriodKey("MONTHLY", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isEqualTo("2026-05");
        // Year boundary.
        assertThat(PeriodKeys.previousPeriodKey("MONTHLY", Instant.parse("2026-01-15T00:00:00Z"), UTC))
                .isEqualTo("2025-12");
    }

    @Test
    void quarterlyKeys() {
        assertThat(PeriodKeys.periodKey("QUARTERLY", Instant.parse("2026-07-01T00:00:00Z"), UTC))
                .isEqualTo("2026-Q3");
        assertThat(PeriodKeys.previousPeriodKey("QUARTERLY", Instant.parse("2026-07-01T00:00:00Z"), UTC))
                .isEqualTo("2026-Q2");
        // Year boundary: Q1's previous quarter is last year's Q4.
        assertThat(PeriodKeys.previousPeriodKey("QUARTERLY", Instant.parse("2026-02-01T00:00:00Z"), UTC))
                .isEqualTo("2025-Q4");
    }

    @Test
    void cadenceSpellingIsNormalized() {
        assertThat(PeriodKeys.periodKey("monthly", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isEqualTo("2026-06");
        assertThat(PeriodKeys.periodKey(" Quarterly ", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isEqualTo("2026-Q2");
    }

    @Test
    void oneShotCommitmentsGetTheLiteralOneTimeKey() {
        assertThat(PeriodKeys.periodKey(null, Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isEqualTo("ONE_TIME");
        assertThat(PeriodKeys.periodKey("  ", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isEqualTo("ONE_TIME");
        assertThat(PeriodKeys.previousPeriodKey(null, Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isNull();
    }

    @Test
    void unsupportedCadencesReturnNullNotAnInventedKey() {
        // Flag-not-invent: §5.1 defines MONTHLY/QUARTERLY only; DAILY/WEEKLY
        // are monitor sweep cadences with no documented period-key semantics.
        assertThat(PeriodKeys.periodKey("WEEKLY", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isNull();
        assertThat(PeriodKeys.previousPeriodKey("DAILY", Instant.parse("2026-06-10T12:00:00Z"), UTC))
                .isNull();
    }

    @Test
    void zoneConfigShiftsTheKeyAtMonthBoundaries() {
        // 2026-06-01T03:00Z is still 2026-05-31 in UTC-5.
        Instant boundary = Instant.parse("2026-06-01T03:00:00Z");
        assertThat(PeriodKeys.periodKey("MONTHLY", boundary, UTC)).isEqualTo("2026-06");
        assertThat(PeriodKeys.periodKey("MONTHLY", boundary, ZoneId.of("America/Chicago")))
                .isEqualTo("2026-05");
    }

    @Test
    void closureBoundaries() {
        // A period is closed once its end passes (§5.1).
        Instant lastDayOfMay = Instant.parse("2026-05-31T23:00:00Z");
        Instant firstOfJune = Instant.parse("2026-06-01T00:00:00Z");
        assertThat(PeriodKeys.isClosed("2026-05", lastDayOfMay, UTC)).isFalse();
        assertThat(PeriodKeys.isClosed("2026-05", firstOfJune, UTC)).isTrue();
        assertThat(PeriodKeys.isClosed("2026-Q1", Instant.parse("2026-04-01T00:00:00Z"), UTC))
                .isTrue();
        assertThat(PeriodKeys.isClosed("2026-Q2", Instant.parse("2026-04-01T00:00:00Z"), UTC))
                .isFalse();
        // ONE_TIME / garbage never "close" — no period end exists.
        assertThat(PeriodKeys.isClosed("ONE_TIME", firstOfJune, UTC)).isFalse();
        assertThat(PeriodKeys.isClosed("not-a-key", firstOfJune, UTC)).isFalse();
        assertThat(PeriodKeys.isClosed(null, firstOfJune, UTC)).isFalse();
    }

    @Test
    void periodEndsAreComparableAcrossCadences() {
        assertThat(PeriodKeys.periodEnd("2026-05")).contains(LocalDate.of(2026, 5, 31));
        assertThat(PeriodKeys.periodEnd("2026-Q1")).contains(LocalDate.of(2026, 3, 31));
        assertThat(PeriodKeys.periodEnd("ONE_TIME")).isEmpty();
        assertThat(PeriodKeys.periodEnd("2026-Q9")).isEmpty();
    }
}
