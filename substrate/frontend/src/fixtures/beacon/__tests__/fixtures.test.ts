import { describe, expect, it } from 'vitest';
import {
  BEACON_CASCADES,
  BEACON_CONTRACTS,
  BEACON_PORTFOLIO,
  BEACON_SUBSIDIARIES,
  CROSS_PORTFOLIO_OPPORTUNITIES,
  DEMO_CURRENT_DATE,
  getCascade,
  getContract,
  getSubsidiary,
} from '@/fixtures/beacon';

/**
 * Referential-integrity tests for the Beacon fixture set (chunk C9).
 *
 * The dashboards navigate by slug/id between surfaces; a broken
 * reference renders a not-found panel instead of crashing, but it is
 * still a fixture bug. These tests pin the invariants the views rely
 * on so fixture edits can't silently break navigation, and they pin the
 * shape of the BEACON_PORTFOLIO swap point the Phase 2–4 live adapters
 * must reproduce.
 */
describe('beacon fixtures', () => {
  it('assembles all 22 subsidiaries with unique slugs', () => {
    expect(BEACON_SUBSIDIARIES).toHaveLength(22);
    const slugs = new Set(BEACON_SUBSIDIARIES.map((s) => s.slug));
    expect(slugs.size).toBe(22);
  });

  it('BEACON_PORTFOLIO (the swap point) is consistent with its parts', () => {
    expect(BEACON_PORTFOLIO.subsidiaries).toBe(BEACON_SUBSIDIARIES);
    expect(BEACON_PORTFOLIO.crossPortfolioOpportunities).toBe(
      CROSS_PORTFOLIO_OPPORTUNITIES,
    );
    expect(BEACON_PORTFOLIO.totalRevenueMillions).toBe(
      BEACON_SUBSIDIARIES.reduce((sum, s) => sum + s.revenueMillions, 0),
    );
    // Fixture-stamped statistics (port-map §5 S6) — engine-computed later.
    expect(BEACON_PORTFOLIO.lifecycleStallCount).toBe(3);
    expect(BEACON_PORTFOLIO.coordinationPatternsThisWeek).toBe(6);
  });

  it('resolves subsidiary lookups by slug and misses safely', () => {
    expect(getSubsidiary('curasync-clinical')?.name).toBe(
      'Curasync Clinical Systems',
    );
    expect(getSubsidiary('not-a-slug')).toBeUndefined();
  });

  it('cross-portfolio opportunities reference real subsidiaries and contracts', () => {
    for (const opp of CROSS_PORTFOLIO_OPPORTUNITIES) {
      for (const slug of opp.affectedSubsidiarySlugs) {
        expect(getSubsidiary(slug), `${opp.id} → ${slug}`).toBeDefined();
      }
      if (opp.contractId) {
        expect(getContract(opp.contractId), `${opp.id} → ${opp.contractId}`).toBeDefined();
      }
    }
  });

  it('cascades carry contiguous levels with state-conditional payloads', () => {
    expect(BEACON_CASCADES.length).toBeGreaterThanOrEqual(3);
    for (const cascade of BEACON_CASCADES) {
      expect(cascade.levels.length).toBeGreaterThanOrEqual(1);
      cascade.levels.forEach((lvl, idx) => {
        expect(lvl.level, `${cascade.id} level order`).toBe(idx);
        if (lvl.state === 'cascaded' || lvl.state === 'absorbed') {
          // Refined critique required for cascaded/absorbed (§6 CASCADE-LEVELS).
          expect(lvl.refinedCritique, `${cascade.id} L${idx}`).toBeTruthy();
        }
        if (lvl.state === 'declined') {
          expect(lvl.declineReasoning, `${cascade.id} L${idx}`).toBeTruthy();
        }
        expect(lvl.timestamp).toMatch(/^\d{4}-\d{2}-\d{2}/);
      });
      if (cascade.affectedSubsidiarySlug) {
        expect(getSubsidiary(cascade.affectedSubsidiarySlug)).toBeDefined();
      }
    }
  });

  it('pins the engine test-vector caveat: c-vendor-cost holds the terminal decline', () => {
    // Port-map §6 fixture caveat: 05-walkthrough.md misattributes the
    // decline to c-tribal-succession; actual fixture content has
    // c-vendor-cost ending declined and c-tribal-succession all-cascaded.
    const vendorCost = getCascade('c-vendor-cost');
    const bottom = vendorCost?.levels[vendorCost.levels.length - 1];
    expect(bottom?.state).toBe('declined');
    const tribal = getCascade('c-tribal-succession');
    expect(tribal?.levels.every((l) => l.state === 'cascaded')).toBe(true);
  });

  it('contracts have 2..N parties with commitments and resolvable links', () => {
    expect(BEACON_CONTRACTS.length).toBeGreaterThanOrEqual(2);
    for (const contract of BEACON_CONTRACTS) {
      expect(contract.parties.length).toBeGreaterThanOrEqual(2);
      for (const party of contract.parties) {
        expect(party.commitments.length).toBeGreaterThanOrEqual(1);
      }
      expect(contract.enforcement.declineTriggers.length).toBeGreaterThanOrEqual(1);
      for (const slug of contract.linkedSubsidiarySlugs) {
        expect(getSubsidiary(slug), `${contract.id} → ${slug}`).toBeDefined();
      }
    }
    expect(getContract('fi-shared-pre-fill-agent')?.type).toBe(
      'federation-invitation',
    );
    expect(getContract('unknown')).toBeUndefined();
  });

  it('exposes the deterministic fixture date as ISO YYYY-MM-DD', () => {
    expect(DEMO_CURRENT_DATE).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
