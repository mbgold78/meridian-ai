import { useMemo } from 'react';
import { getPortfolio } from '@/api/endpoints';
import type { PortfolioViewDto } from '@/api/endpoints';
import { useApiQuery } from '@/shared/hooks/useApi';
import { BEACON_PORTFOLIO } from '@/fixtures/beacon';

/**
 * Portfolio-stats read-model seam — the D5 live swap of the S6
 * fixture-STAMPED statistics on the `BEACON_PORTFOLIO` seam (engine-spec
 * diagnostic.md §12/§14 step 9, ADR-004 D13, port-map §5 S6) — the
 * cascadeData/contractData/agentData pattern verbatim.
 *
 * THE SWAP SWITCH: the Operating-Partner coordination strip renders LIVE
 * engine-computed numbers whenever `GET /api/v1/portfolio` has produced
 * them, and falls back to the fixture constants (`lifecycleStallCount: 3`,
 * `coordinationPatternsThisWeek: 6`) while loading or when the backend is
 * absent. Fixture stats are consumed ONLY inside this module.
 *
 * ABSENT-VS-ZERO RENDERING CONTRACT (spec non-negotiable #4, binding on
 * consumers): `agentsInIntervention === null` means the agent registry is
 * ABSENT — render an em-dash and say so, never `0`. `unphased` is a
 * SEPARATE count from the stall count (§12.1) — surface it next to the
 * stall figure, never fold it in. In fixture mode those fields are `null`
 * (the fixtures never modeled them — `source` tells the strip which
 * vocabulary it is rendering).
 *
 * SUBSIDIARY ROSTER SCOPE NOTE: only the S6 STATISTICS swap here. The
 * roster/detail surfaces still render fixture subsidiaries — the live
 * per-subsidiary swap rides the existing satellite-domain resolution
 * (`agentData.resolveBeaconDomainId`) against
 * `GET /api/v1/portfolio/subsidiaries/{domainId}`, a follow-on slice of the
 * same seam.
 */

export type PortfolioStatsSource = 'live' | 'fixture';

/** The strip's view model — exactly the numbers Zone 2 renders. */
export interface PortfolioStripStats {
  lifecycleStallCount: number;
  coordinationPatternsThisWeek: number;
  /** §12.1 separate count; null in fixture mode (never modeled there). */
  unphased: number | null;
  /** null = unknowable (fixture mode); false = registry ABSENT (render —). */
  agentsAvailable: boolean | null;
  /** null when registry absent/fixture mode — em-dash, never 0. */
  agentsInIntervention: number | null;
  /** Σ subsidiary agent totals when the registry reports; null otherwise. */
  agentsTotal: number | null;
  declinePendingCascades: number | null;
  source: PortfolioStatsSource;
}

/* ------------------------------------------------------------------ */
/*  Adapter — PortfolioViewDto → strip view model (thin, §12.7)        */
/* ------------------------------------------------------------------ */

/**
 * Thin adapter at the `BEACON_PORTFOLIO` seam: passes the engine-computed
 * numbers through verbatim (delegation discipline — the adapter must never
 * recompute what §12 owns) and sums the per-subsidiary agent totals only
 * when the registry actually reported them.
 */
export function adaptPortfolioStats(dto: PortfolioViewDto): PortfolioStripStats {
  const signals = dto.coordinationSignals;
  const agentsTotal = signals.agentsAvailable
    ? dto.subsidiaries.reduce((sum, s) => sum + (s.agents.total ?? 0), 0)
    : null;
  return {
    lifecycleStallCount: signals.lifecycleStallCount,
    coordinationPatternsThisWeek: signals.coordinationPatternsThisWeek,
    unphased: dto.unphased,
    agentsAvailable: signals.agentsAvailable,
    agentsInIntervention: signals.agentsInIntervention,
    agentsTotal,
    declinePendingCascades: signals.declinePendingCascades,
    source: 'live',
  };
}

/** Fixture fallback — the stamped S6 constants, consumed only here. */
export function fixtureStripStats(): PortfolioStripStats {
  return {
    lifecycleStallCount: BEACON_PORTFOLIO.lifecycleStallCount,
    coordinationPatternsThisWeek: BEACON_PORTFOLIO.coordinationPatternsThisWeek,
    unphased: null,
    agentsAvailable: null,
    agentsInIntervention: null,
    agentsTotal: null,
    declinePendingCascades: null,
    source: 'fixture',
  };
}

/* ------------------------------------------------------------------ */
/*  Hook                                                               */
/* ------------------------------------------------------------------ */

/** Strip stats for the Operating-Partner dashboard (spec §14 step 9). */
export function useBeaconPortfolioStats(): PortfolioStripStats {
  const query = useApiQuery<PortfolioViewDto>(
    ['portfolio', 'stats'],
    () => getPortfolio(),
    // retry: false — degraded mode settles on the fixture fallback
    // immediately rather than spinning through backoff (cascadeData idiom).
    { retry: false },
  );
  const live = query.data;
  return useMemo(
    () => (live ? adaptPortfolioStats(live) : fixtureStripStats()),
    [live],
  );
}
