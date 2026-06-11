/**
 * S15 frontend clock seam pins (FIX-07): the seam was built and then
 * forgotten — setPortfolioClock had zero callers AND zero tests, so nothing
 * would catch the findings live swap shipping without arming it. These pins
 * prove the swap mechanism works and document the arming contract.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { DEMO_CURRENT_DATE } from '@/fixtures/beacon';
import {
  FIXTURE_CLOCK,
  LIVE_CLOCK,
  setPortfolioClock,
  todayIso,
} from '@/features/portfolio/clock';

afterEach(() => {
  setPortfolioClock(FIXTURE_CLOCK);
});

describe('portfolio clock seam (S15 frontend half)', () => {
  it('defaults to the fixture clock — coherent with the fixture findings region', () => {
    expect(todayIso()).toBe(DEMO_CURRENT_DATE);
  });

  it('setPortfolioClock swaps the active clock and returns the previous one', () => {
    const prev = setPortfolioClock({ todayIso: () => '2030-01-02' });
    expect(prev).toBe(FIXTURE_CLOCK);
    expect(todayIso()).toBe('2030-01-02');
    setPortfolioClock(prev);
    expect(todayIso()).toBe(DEMO_CURRENT_DATE);
  });

  it('LIVE_CLOCK yields a real UTC date — the one-liner the findings swap must arm', () => {
    setPortfolioClock(LIVE_CLOCK);
    expect(todayIso()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // strictly after the fixture narrative date — the very property that
    // makes an UNARMED clock render "Surfaced 0 days ago" for live findings
    expect(todayIso() > DEMO_CURRENT_DATE).toBe(true);
  });
});
