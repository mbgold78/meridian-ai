package com.meridian.substrate.engine.contract.monitor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Next-cadence-boundary arithmetic for monitor scheduling (engine-spec
 * contracts.md §5.2 step 2: "update {@code next_run_at} (next cadence
 * boundary + jitter ≤ 60 s)"). Pure/static like {@link PeriodKeys}; the
 * jitter is added by the caller ({@code MonitorRunService}) so this stays
 * deterministic for tests.
 *
 * <p>Boundaries (start-of-day in the configured zone):
 * MONTHLY → first day of next month; QUARTERLY → first day of next quarter;
 * WEEKLY → next ISO Monday; DAILY → next midnight. The §3.2 monitor column
 * names exactly these four cadences. An unknown/blank cadence falls back to
 * the next midnight (defensive — keeps a misauthored monitor sweeping daily
 * rather than silently never; never-silent posture, ADR D10).
 */
public final class NextRunTimes {

    private NextRunTimes() {
    }

    /** Next cadence boundary strictly after {@code now}. */
    public static Instant nextBoundary(String cadence, Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        String normalized = cadence == null ? "" : cadence.trim().toUpperCase(Locale.ROOT);
        LocalDate next = switch (normalized) {
            case "MONTHLY" -> today.with(TemporalAdjusters.firstDayOfNextMonth());
            case "QUARTERLY" -> {
                int currentQuarterStartMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                yield LocalDate.of(today.getYear(), currentQuarterStartMonth, 1).plusMonths(3);
            }
            case "WEEKLY" -> today.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
            default -> today.plusDays(1);            // DAILY + defensive fallback
        };
        return next.atStartOfDay(zone).toInstant();
    }
}
