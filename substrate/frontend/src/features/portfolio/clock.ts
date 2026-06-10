import { DEMO_CURRENT_DATE } from '@/fixtures/beacon';

/**
 * Injectable clock for the portfolio dashboards (port-map §2.5 step 9 /
 * §5 S15: DEMO_CURRENT_DATE → injectable clock).
 *
 * homepoc components imported the `DEMO_CURRENT_DATE` constant directly;
 * substrate routes "what is today?" through this seam so:
 *   - demo/fixture mode keeps the deterministic fixed date (default), and
 *   - the Phase 2–4 live swap replaces the active clock with server time
 *     without touching any dashboard component.
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

let activeClock: PortfolioClock = FIXTURE_CLOCK;

/**
 * Swap the active clock (live mode: server time; tests: any fixed date).
 * Returns the previous clock so callers can restore it.
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
