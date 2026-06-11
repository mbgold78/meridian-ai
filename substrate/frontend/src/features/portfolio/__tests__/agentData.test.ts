import { describe, expect, it } from 'vitest';
import {
  adaptAgentFleetRow,
  fixtureToAgentRow,
  liveAgentRows,
  resolveBeaconDomainId,
} from '@/features/portfolio/agentData';
import type { AgentFleetRowDto } from '@/api/endpoints';
import { getSubsidiary } from '@/fixtures/beacon';
import type { BeaconAgent } from '@/fixtures/beacon';
import type { Domain } from '@/shared/types/api';

/**
 * Test plan #18 (engine-spec agents.md §16.2): the `AgentFleetRow →
 * BeaconAgent` adapter deep-equals the fixture objects for the Curasync
 * five; null-adherence em-dash; derived recentActivity fallback chain;
 * plus the §16.2 #2 pill-map guard (`retired` never reaches the
 * `Record<AgentStatus,…>` map).
 *
 * Fixture truth: meridian-homepoc/frontend/src/demo/beacon/subsidiaries/
 * healthcare.ts (substrate copy verified byte-identical, copied 2026-06-11),
 * consumed through the `@/fixtures/beacon` barrel.
 *
 * NAMED GAP, deliberate: `capabilityId` is a UUID soft ref in substrate and
 * Beacon capability rows do not exist, so the fixture slug ids
 * ("eng-intake") are not representable on live rows — the adapter yields
 * `undefined`, never an invented value. The deep-equal below pins every
 * OTHER fixture field byte-identical.
 */

/** What the seeded backend returns for a fixture agent (AgentDemoSeeder §15). */
function dtoFor(agent: BeaconAgent, domainId = 'd-curasync'): AgentFleetRowDto {
  return {
    id: agent.id,
    registrationId: `reg-${agent.id}`,
    domainId,
    name: agent.name,
    role: agent.role,
    status: agent.status,
    operationalMode: agent.status === 'paused' ? 'paused' : 'live',
    // the engine derives recentActivity from the seeded ATTESTATION sighting
    recentActivity: agent.recentActivity,
    // the engine reads the latest seeded 'adherence' metric sample
    adherence: agent.adherence,
    capabilityId: null, // UUID soft ref — fixture slugs not representable (named gap)
    deployedBy: agent.deployedBy ?? 'seed-fiction team',
    deployedAt: `${agent.deployedAt ?? '2026-01-12'}T00:00:00Z`,
    oversightChain: agent.oversightChain ?? ['seed-fiction COO'],
    executionLocus: 'customer-system',
    supervisoryModel: 'human-in-loop',
    openInterventions: agent.status === 'intervention-required' ? 1 : 0,
    eventSeq: 1,
  };
}

const curasync = getSubsidiary('curasync-clinical')!;

describe('adaptAgentFleetRow — Curasync five deep-equal (test plan #18)', () => {
  it.each(curasync.agents.map((a) => [a.id, a] as const))(
    'adapts the seeded backend row for %s back to the fixture object',
    (_id, fixtureAgent) => {
      const adapted = adaptAgentFleetRow(dtoFor(fixtureAgent));
      expect(adapted).toEqual({
        ...fixtureAgent,
        // click-through only — the row shape never carries the mandate
        deploymentCriteria: undefined,
        // named gap (see header): slug capability ids are not representable
        capabilityId: undefined,
      });
    },
  );

  it('covers all four fixture wire statuses across the five', () => {
    const statuses = curasync.agents.map((a) => a.status);
    expect(statuses).toContain('live');
    expect(statuses).toContain('shadow');
    expect(statuses).toContain('paused');
    expect(statuses).toContain('intervention-required');
  });
});

describe('null-adherence em-dash (§13.5 swap rule)', () => {
  it('renders an em-dash for a null adherence, never a fabricated number', () => {
    const dto = { ...dtoFor(curasync.agents[0]), adherence: null };
    expect(adaptAgentFleetRow(dto).adherence).toBe('—');
  });

  it('passes a real adherence sample through untouched (including 0)', () => {
    expect(adaptAgentFleetRow({ ...dtoFor(curasync.agents[0]), adherence: 0 }).adherence).toBe(0);
    expect(adaptAgentFleetRow({ ...dtoFor(curasync.agents[0]), adherence: 96 }).adherence).toBe(96);
  });
});

describe('recentActivity fallback chain (§7.3 — never blank, never invented)', () => {
  it('renders the backend-derived activity verbatim when present', () => {
    expect(adaptAgentFleetRow(dtoFor(curasync.agents[0])).recentActivity).toBe(
      '14,212 forms pre-filled this week',
    );
  });

  it('falls back to "No activity observed" when the backend field is blank', () => {
    const blank = { ...dtoFor(curasync.agents[0]), recentActivity: '' };
    expect(adaptAgentFleetRow(blank).recentActivity).toBe('No activity observed');
    const whitespace = { ...dtoFor(curasync.agents[0]), recentActivity: '   ' };
    expect(adaptAgentFleetRow(whitespace).recentActivity).toBe('No activity observed');
  });
});

describe('retired pill-map guard (§16.2 #2; ADR-003 D4)', () => {
  it('drops retired rows so Record<AgentStatus,…> can never see an unknown status', () => {
    const rows = liveAgentRows([
      dtoFor(curasync.agents[0]),
      { ...dtoFor(curasync.agents[1]), status: 'retired', operationalMode: 'retired' },
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].id).toBe(curasync.agents[0].id);
    // AgentRow.status is the FIXTURE union — 'retired' is not even a member;
    // widen to string to prove no runtime leak either.
    expect(rows.map((r) => r.status as string)).not.toContain('retired');
  });
});

describe('fixture fallback + domain resolution', () => {
  it('fixtureToAgentRow is a pass-through (fixture adherence is already a number)', () => {
    expect(fixtureToAgentRow(curasync.agents[3])).toEqual(curasync.agents[3]);
  });

  it('resolves the satellite domain by metadata.beaconSlug first, name second', () => {
    const domains = [
      domain('d-1', 'Meridian Bancorp · Risk Consolidation', null),
      domain('d-2', 'Curasync Clinical Systems', { beaconSubsidiary: true, beaconSlug: 'curasync-clinical' }),
      domain('d-3', 'RevSync Health Financial', null),
    ];
    expect(resolveBeaconDomainId(domains, curasync)).toBe('d-2');
    expect(
      resolveBeaconDomainId(domains, { slug: 'revsync-health', name: 'RevSync Health Financial' }),
    ).toBe('d-3');
    expect(
      resolveBeaconDomainId(domains, { slug: 'no-such', name: 'No Such Subsidiary' }),
    ).toBeNull();
  });
});

function domain(id: string, name: string, metadata: Record<string, unknown> | null): Domain {
  return {
    id,
    name,
    description: null,
    taxonomyConfig: null,
    regulatoryContext: null,
    metadata,
    demo: true,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
  };
}
