/**
 * Beacon Industries fixtures — entry point AND the documented swap point.
 *
 * ── PORT NOTE (substrate chunk C9, port-map §5 S5 / §7 C9) ──────────────
 * Every value in this module is fixture data retained TEMPORARILY as the
 * Phase 1 mock-data source for the four Beacon dashboards under
 * `src/features/portfolio/`. Nothing here talks to a backend.
 *
 * THE SINGLE SWAP POINT is the {@link BEACON_PORTFOLIO} factory below
 * (plus the sibling lookup helpers it anchors: {@link getSubsidiary},
 * {@link getCascade}, {@link getContract}). Phases 2–4 replace the
 * fixture-backed implementations with React Query hooks + adapters from
 * substrate DTOs to the Beacon* view types — the dashboard components
 * import ONLY from this module (`@/fixtures/beacon`), never from the
 * sibling files, so the swap happens in one place.
 *
 * ── PHASE 2 STATUS: CASCADES ARE SWAPPED ────────────────────────────────
 * The cascade slice now rides the live read API (engine-spec cascade.md
 * §11, ADR-001 D13). Portfolio surfaces consume cascades exclusively
 * through the hooks in `@/features/portfolio/cascadeData`
 * (`useBeaconCascades` / `useBeaconCascade` / `useBeaconCascadeActions`
 * over GET /api/v1/cascades*). {@link BEACON_CASCADES} / {@link getCascade}
 * remain exported ONLY as the degraded/demo fallback consumed inside that
 * seam: live data renders whenever the API has produced it; these fixtures
 * render while it is loading or when the backend is absent.
 *
 * ── PHASE 3a STATUS: CONTRACTS ARE SWAPPED ──────────────────────────────
 * The contract slice rides the live read API the same way (engine-spec
 * contracts.md §11.3, ADR-002). The contract detail surface consumes
 * contracts exclusively through `useBeaconContract` in
 * `@/features/portfolio/contractData` (over GET /api/v1/contracts/{idOrKey}
 * + the §7.3 adapter). {@link BEACON_CONTRACTS} / {@link getContract}
 * remain exported ONLY as the degraded/demo fallback consumed inside that
 * seam. Subsidiaries and the portfolio stats below are still Phase-1
 * fixtures (the S6 signal-strip stats swap to GET /contracts/signals in a
 * later chunk).
 *
 * The narrative content (subsidiary names, critiques, contracts) is
 * FICTIONAL (port-map appendix #9). It must never ship in a customer
 * engagement build — excise at route + directory level when the live
 * swap happens.
 * ────────────────────────────────────────────────────────────────────────
 */

import { CREATIVE_SUBSIDIARIES } from './subsidiaries/creative';
import { EDUCATION_SUBSIDIARIES } from './subsidiaries/education';
import { FINANCIAL_SUBSIDIARIES } from './subsidiaries/financial';
import { GOVERNMENT_SUBSIDIARIES } from './subsidiaries/government';
import { HEALTHCARE_SUBSIDIARIES } from './subsidiaries/healthcare';
import { INSTRUMENTATION_SUBSIDIARIES } from './subsidiaries/instrumentation';
import { INSURANCE_SUBSIDIARIES } from './subsidiaries/insurance';
import { LEGAL_SUBSIDIARIES } from './subsidiaries/legal';
import { LOGISTICS_SUBSIDIARIES } from './subsidiaries/logistics';
import {
  BEACON_COMPANY_NAME,
  BEACON_HQ_CITY,
  CROSS_PORTFOLIO_OPPORTUNITIES,
  buildCoordinationSignals,
} from './portfolio';
import type {
  BeaconCapability,
  BeaconPortfolio,
  BeaconSubsidiary,
  Industry,
  LifecyclePhase,
} from './types';

export * from './types';
export {
  BEACON_COMPANY_NAME,
  BEACON_HQ_CITY,
  CROSS_PORTFOLIO_OPPORTUNITIES,
} from './portfolio';
// Cascade + contract fixtures are re-exported through this index so the
// portfolio feature has exactly one fixture entry point (homepoc let the
// dashboard deep-import `@/demo/beacon/cascades`; substrate routes all
// consumption through the swap point).
export { BEACON_CASCADES, getCascade } from './cascades';
export { BEACON_CONTRACTS, getContract } from './contracts';

/**
 * Canonical "today" for the Beacon fixture set. Surfaces that compute
 * relative dates (e.g. "21 days ago") read against the injectable clock
 * in `@/features/portfolio/clock` (which defaults to this constant)
 * rather than `new Date()` so the demo stays deterministic across
 * reloads and dates don't drift between recordings.
 *
 * Set as an ISO date (YYYY-MM-DD). Update only when the fixture set's
 * narrative reference point changes. In production the clock seam is
 * swapped to server time (port-map §5 S15).
 */
export const DEMO_CURRENT_DATE = '2026-04-26';

/**
 * Canonical, deterministic ordering of all 22 subsidiaries. The demo
 * dashboards iterate this array directly so visual ordering stays
 * stable across reloads.
 */
export const BEACON_SUBSIDIARIES: BeaconSubsidiary[] = [
  ...HEALTHCARE_SUBSIDIARIES,
  ...GOVERNMENT_SUBSIDIARIES,
  ...INSURANCE_SUBSIDIARIES,
  ...INSTRUMENTATION_SUBSIDIARIES,
  ...FINANCIAL_SUBSIDIARIES,
  ...LEGAL_SUBSIDIARIES,
  ...LOGISTICS_SUBSIDIARIES,
  ...EDUCATION_SUBSIDIARIES,
  ...CREATIVE_SUBSIDIARIES,
];

const subsidiariesBySlug: Map<string, BeaconSubsidiary> = new Map(
  BEACON_SUBSIDIARIES.map((s) => [s.slug, s]),
);

/**
 * Look up a subsidiary by slug. Returns undefined when the slug isn't
 * known — callers should treat missing as a 404 in the router.
 */
export function getSubsidiary(slug: string): BeaconSubsidiary | undefined {
  return subsidiariesBySlug.get(slug);
}

export function listSubsidiariesByIndustry(industry: Industry): BeaconSubsidiary[] {
  return BEACON_SUBSIDIARIES.filter((s) => s.industry === industry);
}

export function listSubsidiariesByPhase(phase: LifecyclePhase): BeaconSubsidiary[] {
  return BEACON_SUBSIDIARIES.filter((s) => s.lifecyclePhase === phase);
}

/**
 * Walks the subsidiary's flat capability list and returns a parent →
 * children map convenient for tree rendering. Children are returned in
 * declaration order, which is the order the fixture authors chose.
 */
export function buildCapabilityChildren(
  sub: BeaconSubsidiary,
): Map<string | null, BeaconCapability[]> {
  const map = new Map<string | null, BeaconCapability[]>();
  for (const c of sub.capabilities) {
    const bucket = map.get(c.parentId) ?? [];
    bucket.push(c);
    map.set(c.parentId, bucket);
  }
  return map;
}

const TOTAL_REVENUE_MILLIONS = BEACON_SUBSIDIARIES.reduce(
  (sum, s) => sum + s.revenueMillions,
  0,
);

export const COORDINATION_SIGNALS = buildCoordinationSignals(
  BEACON_SUBSIDIARIES,
  CROSS_PORTFOLIO_OPPORTUNITIES.length,
);

/**
 * Single point of access to the assembled portfolio — THE swap point.
 * The dashboard components read from this object rather than
 * cherry-picking individual exports so the Phase 2–4 live swap (read
 * APIs + adapters) only needs to replace this one factory.
 *
 * `lifecycleStallCount` / `coordinationPatternsThisWeek` are
 * fixture-stamped statistics (port-map §5 S6) — they become
 * engine-computed when the live swap happens.
 */
export const BEACON_PORTFOLIO: BeaconPortfolio = {
  companyName: BEACON_COMPANY_NAME,
  hqCity: BEACON_HQ_CITY,
  totalRevenueMillions: TOTAL_REVENUE_MILLIONS,
  subsidiaries: BEACON_SUBSIDIARIES,
  crossPortfolioOpportunities: CROSS_PORTFOLIO_OPPORTUNITIES,
  coordinationSignals: COORDINATION_SIGNALS,
  lifecycleStallCount: 3,
  coordinationPatternsThisWeek: 6,
};
