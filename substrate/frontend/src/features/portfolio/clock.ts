import { DEMO_CURRENT_DATE } from '@/fixtures/beacon';

/**
 * Injectable clock for the portfolio dashboards (port-map §2.5 step 9 /
 * §5 S15: DEMO_CURRENT_DATE → injectable clock).
 *
 * homepoc components imported the `DEMO_CURRENT_DATE` constant directly;
 * substrate routes "what is today?" through this seam so:
 *   - demo/fixture mode keeps the deterministic fixed date (default), and
 *   - the findings live swap replaces the active clock with real time
 *     without touching any dashboard component.
 *
 * SEAM STATUS (honest — diagnostic.md §12.4 correction): the seam is built
 * but NOT YET ARMED. `setPortfolioClock` has no production caller; the
 * active clock is the fixture date, which is CORRECT while the sole
 * consumer ("Surfaced N days ago" in OperationsTab) renders FIXTURE
 * findings — fixture dates and the fixture clock are mutually consistent.
 * BINDING ON THE FINDINGS LIVE SWAP (FIX-06 slice): the change that feeds
 * OperationsTab live findings MUST swap in {@link LIVE_CLOCK} in the same
 * commit, or every real finding surfaced after the fixture date renders
 * "Surfaced 0 days ago" forever (daysBetween clamps from>to to 0). Do not
 * trust the cascade.md checklist line "already handled by C9/S15" — that
 * covers the seam EXISTING, not the swap having happened.
 *
 * The clock returns an ISO date string (YYYY-MM-DD) because every
 * consumer computes whole-day deltas in UTC.
 */
export interface PortfolioClock {
  /** Current date as an ISO YYYY-MM-DD string. */
  todayIso(): string;
}

/** Deterministic fixture clock — pins "today" to the fixture narrative date. */
export const FIXTURE_CLOCK: PortfolioClock = {
  todayIso: () => DEMO_CURRENT_DATE,
};

/**
 * Real-time clock (UTC date) — what the findings live swap arms via
 * {@link setPortfolioClock}. Defined here so the swap is a one-liner and the
 * seam test pins it works before any production caller exists.
 */
export const LIVE_CLOCK: PortfolioClock = {
  todayIso: () => new Date().toISOString().slice(0, 10),
};

let activeClock: PortfolioClock = FIXTURE_CLOCK;

/**
 * Swap the active clock (live mode: {@link LIVE_CLOCK}; tests: any fixed
 * date). Returns the previous clock so callers can restore it.
 */
export function setPortfolioClock(next: PortfolioClock): PortfolioClock {
  const prev = activeClock;
  activeClock = next;
  return prev;
}

/** Current date per the active clock, as ISO YYYY-MM-DD. */
export function todayIso(): string {
  return activeClock.todayIso();
}
