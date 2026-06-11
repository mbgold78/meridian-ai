import { describe, expect, it } from 'vitest';
import {
  adaptPortfolioStats,
  fixtureStripStats,
} from '@/features/portfolio/portfolioData';
import type { PortfolioStripStats } from '@/features/portfolio/portfolioData';
import type {
  PortfolioAgentStatsDto,
  PortfolioSubsidiaryCardDto,
  PortfolioViewDto,
} from '@/api/endpoints';
import { BEACON_PORTFOLIO } from '@/fixtures/beacon';

/**
 * D5 adapter pins at the BEACON_PORTFOLIO seam (engine-spec diagnostic.md
 * §12.7; test plan #14 — "adapter deep-equals … frontend vitest"):
 * the wire DTO maps onto the strip view model verbatim (the adapter never
 * recomputes what §12 owns), the absent-vs-zero distinction survives the
 * adapter structurally, and the fixture fallback is exactly the stamped S6
 * constants.
 */

function agents(overrides: Partial<PortfolioAgentStatsDto> = {}): PortfolioAgentStatsDto {
  return {
    available: true,
    total: 0,
    live: 0,
    shadow: 0,
    paused: 0,
    interventionRequired: 0,
    ...overrides,
  };
}

function card(
  overrides: Partial<PortfolioSubsidiaryCardDto> = {},
): PortfolioSubsidiaryCardDto {
  return {
    domainId: 'dom-1',
    name: 'Curasync Clinical Systems',
    lifecyclePhase: 'design',
    phaseStatesUninitialized: false,
    lifecycleStalled: false,
    findingCounts: {
      bySeverity: {},
      aiGenerated: 0,
      operatorAuthored: 0,
      clientSurfaceBySeverity: {},
    },
    agents: agents(),
    pendingFederationInvitations: 0,
    aiReadiness: null,
    ...overrides,
  };
}

function view(overrides: Partial<PortfolioViewDto> = {}): PortfolioViewDto {
  return {
    companyName: 'Substrate Engagement',
    subsidiaries: [
      card({ domainId: 'dom-1', agents: agents({ total: 4, interventionRequired: 1 }) }),
      card({
        domainId: 'dom-2',
        name: 'Polestar Life',
        lifecyclePhase: null,
        phaseStatesUninitialized: true,
        agents: agents({ total: 3 }),
      }),
    ],
    coordinationSignals: {
      lifecycleStallCount: 1,
      coordinationPatternsThisWeek: 9,
      coordinationBreakdown: { contractEvents: 3, cascadeActions: 6 },
      declinePendingCascades: 2,
      agentsAvailable: true,
      agentsInIntervention: 1,
      needsInputByKind: {},
      pendingFederationInvitations: 2,
    },
    crossPortfolioOpportunities: [],
    unphased: 1,
    computedFrom: {
      computedAt: '2026-06-15T00:00:00Z',
      stallThresholdDays: 90,
      coordinationWindowDays: 7,
      demoDomainsIncluded: true,
      domainsConsidered: 2,
      agentRegistryAvailable: true,
      aiReadiness: 'omitted — no computation basis until the D3 readiness lens lands',
      sources: {},
    },
    ...overrides,
  };
}

describe('adaptPortfolioStats', () => {
  it('deep-equals the strip view model — engine numbers pass through verbatim', () => {
    const expected: PortfolioStripStats = {
      lifecycleStallCount: 1,
      coordinationPatternsThisWeek: 9,
      unphased: 1,
      agentsAvailable: true,
      agentsInIntervention: 1,
      agentsTotal: 7,
      declinePendingCascades: 2,
      source: 'live',
    };
    expect(adaptPortfolioStats(view())).toEqual(expected);
  });

  it('keeps the absent registry structurally distinct from zero', () => {
    const absent = view({
      subsidiaries: [
        card({
          agents: {
            available: false,
            total: null,
            live: null,
            shadow: null,
            paused: null,
            interventionRequired: null,
          },
        }),
      ],
      coordinationSignals: {
        ...view().coordinationSignals,
        agentsAvailable: false,
        agentsInIntervention: null,
      },
    });
    const stats = adaptPortfolioStats(absent);
    // null, never 0 — the renderer shows an em-dash + "registry unavailable".
    expect(stats.agentsAvailable).toBe(false);
    expect(stats.agentsInIntervention).toBeNull();
    expect(stats.agentsTotal).toBeNull();
  });

  it('carries unphased as a SEPARATE count — never folded into the stall figure', () => {
    const stats = adaptPortfolioStats(view({ unphased: 21 }));
    expect(stats.unphased).toBe(21);
    expect(stats.lifecycleStallCount).toBe(1);
  });
});

describe('fixtureStripStats', () => {
  it('is exactly the stamped S6 constants, marked as fixture vocabulary', () => {
    const expected: PortfolioStripStats = {
      lifecycleStallCount: BEACON_PORTFOLIO.lifecycleStallCount,
      coordinationPatternsThisWeek: BEACON_PORTFOLIO.coordinationPatternsThisWeek,
      unphased: null,
      agentsAvailable: null,
      agentsInIntervention: null,
      agentsTotal: null,
      declinePendingCascades: null,
      source: 'fixture',
    };
    expect(fixtureStripStats()).toEqual(expected);
  });
});
