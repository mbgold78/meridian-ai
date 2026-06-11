import { describe, expect, it } from 'vitest';
import {
  adaptCascadeDetail,
  adaptCascadeLevel,
  adaptCascadeSummary,
  attentionOrder,
  fixtureToGridRow,
  resolveBeaconCascadeDomainId,
} from '@/features/portfolio/cascadeData';
import type { CascadeGridRow } from '@/features/portfolio/cascadeData';
import type { CascadeDetailDto, CascadeLevelDto, CascadeSummaryDto } from '@/api/endpoints';
import { BEACON_CASCADES, getCascade } from '@/fixtures/beacon';

/**
 * Unit pins for the cascade read-model adapters (engine-spec §11,
 * ADR-001 D13): DTO levels[] map 1:1 onto the Beacon CascadeLevel view
 * type, the fixture fallback derives signals exactly as the engine
 * projects them, and the issued-cascades grid sorts in operator
 * attention order.
 */

const SUMMARY: CascadeSummaryDto = {
  id: 'cas-1',
  originatedBy: 'Operating Partner',
  originatedByMemberId: null,
  originatedAt: '2026-04-08T00:00:00Z',
  originalCritique: 'Critique text.',
  acceptanceCriteria: 'Criteria text.',
  summary: null,
  currentState: 'in-flight',
  attentionSignal: 'decline-pending',
  overdue: false,
  levelCount: 3,
  frontierDepth: 2,
  frontierState: 'declined',
  frontierOwnerLabel: 'Curasync HIM Director',
  lastActivityAt: '2026-04-22T00:00:00Z',
  affectedDomainId: 'dom-2',
  affectedDomainName: 'Curasync Clinical Systems',
  cascadeGroupId: null,
  predecessorCascadeId: null,
  chainSeq: 4,
};

const LOOKING_LEVEL: CascadeLevelDto = {
  level: 2,
  ownerLabel: 'Sentari Senior MT',
  ownerMemberId: null,
  state: 'looking',
  round: 1,
  refinedCritique: null,
  declineReasoning: null,
  activatedAt: '2026-04-24T00:00:00Z',
  actedAt: null,
  timestamp: '2026-04-24',
  responseDeadline: null,
  overdue: false,
};

describe('adaptCascadeSummary', () => {
  it('maps the DTO onto the grid-row shape', () => {
    const row = adaptCascadeSummary(SUMMARY);
    expect(row).toEqual<CascadeGridRow>({
      id: 'cas-1',
      originalCritique: 'Critique text.',
      originatedAt: '2026-04-08T00:00:00Z',
      currentState: 'in-flight',
      attentionSignal: 'decline-pending',
      overdue: false,
      levelCount: 3,
      frontierDepth: 2,
      lastActivityAt: '2026-04-22T00:00:00Z',
    });
  });
});

describe('adaptCascadeLevel', () => {
  it('passes LOOKING rows through (engine-only state the fixtures never contained)', () => {
    expect(adaptCascadeLevel(LOOKING_LEVEL)).toEqual({
      level: 2,
      ownerLabel: 'Sentari Senior MT',
      state: 'looking',
      refinedCritique: undefined,
      declineReasoning: undefined,
      timestamp: '2026-04-24',
    });
  });

  it('falls back to actedAt/activatedAt when timestamp is absent (defensive only)', () => {
    const noTimestamp = {
      ...LOOKING_LEVEL,
      timestamp: undefined,
    } as unknown as CascadeLevelDto;
    expect(adaptCascadeLevel(noTimestamp).timestamp).toBe('2026-04-24T00:00:00Z');
  });
});

describe('adaptCascadeDetail', () => {
  it('produces a BeaconCascade with kebab-case states intact and nulls dropped', () => {
    const detail: CascadeDetailDto = {
      ...SUMMARY,
      levels: [
        {
          level: 0,
          ownerLabel: 'Operating Partner',
          ownerMemberId: null,
          state: 'cascaded',
          round: 1,
          refinedCritique: 'Refined.',
          declineReasoning: null,
          activatedAt: '2026-04-08T00:00:00Z',
          actedAt: '2026-04-08T00:00:00Z',
          timestamp: '2026-04-08',
          responseDeadline: null,
          overdue: false,
        },
        LOOKING_LEVEL,
      ],
      resolution: null,
      siblingDeclineAdvisories: [],
    };
    const cascade = adaptCascadeDetail(detail);
    expect(cascade.id).toBe('cas-1');
    expect(cascade.currentState).toBe('in-flight');
    expect(cascade.levels).toHaveLength(2);
    expect(cascade.levels[0].refinedCritique).toBe('Refined.');
    expect(cascade.levels[0].declineReasoning).toBeUndefined();
    expect(cascade.levels[1].state).toBe('looking');
  });
});

describe('fixtureToGridRow', () => {
  it('derives decline-pending for the c-vendor-cost terminal-decline vector', () => {
    const row = fixtureToGridRow(getCascade('c-vendor-cost')!);
    expect(row.attentionSignal).toBe('decline-pending');
    expect(row.frontierDepth).toBe(2);
    expect(row.levelCount).toBe(3);
    expect(row.overdue).toBe(false);
  });

  it('derives escalating for the cascaded-still-out vectors', () => {
    expect(fixtureToGridRow(getCascade('c-tribal-succession')!).attentionSignal).toBe(
      'escalating',
    );
    expect(fixtureToGridRow(getCascade('c-tellix-velocity')!).attentionSignal).toBe(
      'escalating',
    );
  });

  it('covers every fixture cascade without throwing', () => {
    expect(BEACON_CASCADES.map(fixtureToGridRow)).toHaveLength(BEACON_CASCADES.length);
  });
});

describe('attentionOrder (spec §11 item 4)', () => {
  const base = adaptCascadeSummary(SUMMARY);
  const row = (overrides: Partial<CascadeGridRow>): CascadeGridRow => ({
    ...base,
    ...overrides,
  });

  it('sorts decline-pending, then overdue, then escalating by lastActivityAt desc', () => {
    const escalatingOld = row({
      id: 'old',
      attentionSignal: 'escalating',
      lastActivityAt: '2026-04-10T00:00:00Z',
    });
    const escalatingNew = row({
      id: 'new',
      attentionSignal: 'escalating',
      lastActivityAt: '2026-04-20T00:00:00Z',
    });
    const overdue = row({
      id: 'overdue',
      attentionSignal: 'escalating',
      overdue: true,
      lastActivityAt: '2026-04-01T00:00:00Z',
    });
    const declinePending = row({
      id: 'decline',
      attentionSignal: 'decline-pending',
      lastActivityAt: '2026-03-01T00:00:00Z',
    });

    const sorted = attentionOrder([escalatingOld, escalatingNew, overdue, declinePending]);
    expect(sorted.map((r) => r.id)).toEqual(['decline', 'overdue', 'new', 'old']);
  });

  it('does not mutate its input', () => {
    const input = [row({ id: 'a' }), row({ id: 'b', attentionSignal: 'escalating' })];
    const snapshot = input.map((r) => r.id);
    attentionOrder(input);
    expect(input.map((r) => r.id)).toEqual(snapshot);
  });
});

describe('resolveBeaconCascadeDomainId (FIX-05: pin > demo > workspace)', () => {
  const domain = (id: string, demo: boolean) =>
    ({
      id,
      name: id,
      description: null,
      taxonomyConfig: null,
      regulatoryContext: null,
      metadata: null,
      demo,
      createdAt: '2026-06-11T00:00:00Z',
      updatedAt: '2026-06-11T00:00:00Z',
    }) as import('@/shared/types/api').Domain;

  it('an explicit deploy-time pin overrides the demo-first preference', () => {
    const domains = [domain('demo-1', true), domain('real-1', false)];
    expect(resolveBeaconCascadeDomainId(domains, 'real-1', 'real-1')).toBe('real-1');
  });

  it('without a pin, the demo-seeded domain wins (demo coherence, spec §11.4)', () => {
    const domains = [domain('real-1', false), domain('demo-1', true)];
    expect(resolveBeaconCascadeDomainId(domains, null, 'real-1')).toBe('demo-1');
  });

  it('a pin that matches no fetched domain falls through to demo-then-workspace', () => {
    const domains = [domain('demo-1', true)];
    expect(resolveBeaconCascadeDomainId(domains, 'missing', 'demo-1')).toBe('demo-1');
  });

  it('no demo domain: the workspace-resolved domain binds', () => {
    const domains = [domain('real-1', false)];
    expect(resolveBeaconCascadeDomainId(domains, null, 'real-1')).toBe('real-1');
  });
});
