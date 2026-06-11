package com.meridian.substrate.engine.contract.monitor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Period-key computation (engine-spec contracts.md §5.1): MONTHLY →
 * {@code YYYY-MM}; QUARTERLY → {@code YYYY-Qn}; one-shot commitments (null
 * cadence) → literal {@code ONE_TIME}. Keys are computed from the injectable
 * engine {@code java.time.Clock} in the {@code substrate.contracts.clock.zone}
 * zone — callers pass the instant + zone, this class stays pure/static.
 *
 * <p>A period is <em>closed</em> once its end passes (§5.1); grace arithmetic
 * ({@code grace_days} after period end) belongs to the 3b evaluators, not
 * here.
 *
 * <p>Flag-not-invent: §5.1 defines period keys for MONTHLY and QUARTERLY
 * only. Other cadence spellings (the §3.2 monitor column mentions DAILY /
 * WEEKLY for sweep scheduling) have no documented period-key semantics —
 * {@link #periodKey} returns {@code null} for them so callers surface
 * "not computable" rather than an invented key (extension point; unblocks
 * with the first engagement needing sub-monthly attestation periods).
 */
public final class PeriodKeys {

    /** §5.1 literal period key for one-shot (cadence-less) commitments. */
    public static final String ONE_TIME = "ONE_TIME";

    public static final String MONTHLY = "MONTHLY";
    public static final String QUARTERLY = "QUARTERLY";

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    private PeriodKeys() {
    }

    /**
     * Period key containing {@code at} for the given cadence; {@code ONE_TIME}
     * for null/blank cadence; {@code null} for cadences without §5.1 key
     * semantics (see class javadoc).
     */
    public static String periodKey(String cadence, Instant at, ZoneId zone) {
        String normalized = normalize(cadence);
        if (normalized == null) {
            return ONE_TIME;
        }
        LocalDate date = at.atZone(zone).toLocalDate();
        return switch (normalized) {
            case MONTHLY -> MONTH_KEY.format(date);
            case QUARTERLY -> date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
            default -> null;
        };
    }

    /**
     * Most recent CLOSED period key as of {@code at} (the period before the
     * one containing {@code at}) — what a cadence-bearing party can be missing
     * an attestation for "now". Null for one-shot or unsupported cadences.
     */
    public static String previousPeriodKey(String cadence, Instant at, ZoneId zone) {
        String normalized = normalize(cadence);
        if (normalized == null) {
            return null;
        }
        LocalDate date = at.atZone(zone).toLocalDate();
        return switch (normalized) {
            case MONTHLY -> MONTH_KEY.format(YearMonth.from(date).minusMonths(1));
            case QUARTERLY -> {
                LocalDate prevQuarter = date.minusMonths(3);
                yield prevQuarter.getYear() + "-Q" + ((prevQuarter.getMonthValue() - 1) / 3 + 1);
            }
            default -> null;
        };
    }

    /**
     * Whether a {@code YYYY-MM} / {@code YYYY-Qn} period key is closed as of
     * {@code at} (§5.1: closed once its end passes). {@code ONE_TIME} and
     * unparseable keys are never "closed" — they have no period end.
     */
    public static boolean isClosed(String periodKey, Instant at, ZoneId zone) {
        LocalDate today = at.atZone(zone).toLocalDate();
        return periodEnd(periodKey).map(today::isAfter).orElse(false);
    }

    /**
     * Last calendar day of a {@code YYYY-MM} / {@code YYYY-Qn} period key;
     * empty for {@code ONE_TIME} / unparseable keys (no period end exists).
     * Also the comparison currency for "latest complete period" (§4.4 metric
     * leaf) — period keys of different cadences are not lexicographically
     * comparable, their end dates are.
     */
    public static java.util.Optional<LocalDate> periodEnd(String periodKey) {
        if (periodKey == null || periodKey.isBlank() || ONE_TIME.equals(periodKey)) {
            return java.util.Optional.empty();
        }
        try {
            if (periodKey.matches("\\d{4}-\\d{2}")) {
                return java.util.Optional.of(YearMonth.parse(periodKey).atEndOfMonth());
            }
            if (periodKey.matches("\\d{4}-Q[1-4]")) {
                int year = Integer.parseInt(periodKey.substring(0, 4));
                int quarter = Integer.parseInt(periodKey.substring(6));
                return java.util.Optional.of(YearMonth.of(year, quarter * 3).atEndOfMonth());
            }
        } catch (RuntimeException unparseable) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }

    private static String normalize(String cadence) {
        if (cadence == null || cadence.isBlank()) {
            return null;
        }
        return cadence.trim().toUpperCase(Locale.ROOT);
    }
}
