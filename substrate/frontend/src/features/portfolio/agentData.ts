import { useMemo } from 'react';
import { getAgentByIdOrKey, getAgentSignals, getAgents } from '@/api/endpoints';
import type { AgentDetailDto, AgentFleetRowDto, AgentSignalsDto } from '@/api/endpoints';
import { useApiQuery } from '@/shared/hooks/useApi';
import { useWorkspace } from '@/shared/hooks/useWorkspace';
import type { Domain } from '@/shared/types/api';
import type { BeaconAgent, BeaconSubsidiary } from '@/fixtures/beacon';

/**
 * Agent read-model seam — the Phase 6a live swap of the agent slice of the
 * `BEACON_PORTFOLIO` fixture seam (engine-spec agents.md §13.5, ADR-003
 * D17/D18, port-map §5 S5) — the cascadeData/contractData pattern verbatim.
 *
 * THE SWAP SWITCH: OperationsTab renders LIVE agents whenever the read API
 * has produced them and falls back to the fixture `sub.agents` while loading
 * or when the backend is absent (degraded/demo determinism). Fixture agents
 * are consumed ONLY inside this module.
 *
 * Domain binding: a Beacon subsidiary IS a Domain (cascade D13) —
 * `AgentDemoSeeder` creates one isDemo satellite domain per subsidiary with
 * `metadata.beaconSlug`; resolution matches the slug first, the subsidiary
 * name second, and stays on the fixture fallback when neither resolves
 * (backend absent, or seeded with `substrate.demo.seed=false` ⇒ no satellite
 * domains ⇒ fixtures render).
 *
 * §16.2 #2 pill-map guard: the additive `retired` wire value (Mike-flagged,
 * ADR-003 D4) NEVER reaches the `Record<AgentStatus,…>` pill map — the
 * backend excludes it from fleet lists by default AND `liveAgentRows` drops
 * it defensively, so the map cannot crash on an unknown status.
 *
 * Read-only by design: no verb hooks exist here — pause/kill affordances,
 * the 409-refresh pattern and the liveness line are 6b detail-panel work
 * shipping WITH their API (spec §13.5). // EXTENSION POINT(agent-verbs-6b)
 */

/**
 * The OperationsTab row shape: `BeaconAgent` with the §13.5 null-adherence
 * rule applied — a null `adherence` renders an EM-DASH, never a fabricated
 * number (the fixture type requires the field, so the adapter must fill it
 * or rows break).
 */
export type AgentRow = Omit<BeaconAgent, 'adherence'> & { adherence: number | '—' };

/* ------------------------------------------------------------------ */
/*  Adapters — API DTOs → Beacon view types (thin, spec §7.3)          */
/* ------------------------------------------------------------------ */

/**
 * `AgentFleetRow → BeaconAgent` (spec §7.3: the DTO is a BeaconAgent
 * superset, so the adapter is THIN): id ← agentKey; status passes through —
 * the wire vocabulary IS the fixture vocabulary; recentActivity is
 * backend-derived (sighting → lifecycle event → "No activity observed") with
 * a defensive same-string fallback here; deployedAt narrows the ISO instant
 * to the fixture's YYYY-MM-DD. `capabilityId` is a NAMED GAP: the substrate
 * column is a UUID soft ref and Beacon capability rows do not exist, so the
 * fixture's slug ids ("eng-intake") are not representable yet — undefined,
 * never invented.
 */
export function adaptAgentFleetRow(dto: AgentFleetRowDto): AgentRow {
  return {
    id: dto.id,
    name: dto.name,
    role: dto.role,
    // `retired` is filtered before this adapter runs (liveAgentRows);
    // remaining values are exactly the fixture AgentStatus union.
    status: dto.status as BeaconAgent['status'],
    recentActivity:
      dto.recentActivity && dto.recentActivity.trim().length > 0
        ? dto.recentActivity
        : 'No activity observed',
    adherence: dto.adherence ?? '—',
    capabilityId: undefined,
    deployedBy: dto.deployedBy,
    deployedAt: dto.deployedAt ? dto.deployedAt.slice(0, 10) : undefined,
    deploymentCriteria: undefined, // CLICK-THROUGH ONLY — never row-level ([AGENT-PROVENANCE])
    oversightChain:
      dto.oversightChain && dto.oversightChain.length > 0 ? dto.oversightChain : undefined,
  };
}

/**
 * Live fleet → OperationsTab rows: drops `retired` (pill-map guard, §16.2
 * #2) then adapts. Exported pure so the guard is unit-testable.
 */
export function liveAgentRows(dtos: AgentFleetRowDto[]): AgentRow[] {
  return dtos.filter((dto) => dto.status !== 'retired').map(adaptAgentFleetRow);
}

/** Fixture fallback — `BeaconAgent` is already row-shaped (adherence is a number). */
export function fixtureToAgentRow(agent: BeaconAgent): AgentRow {
  return { ...agent };
}

/* ------------------------------------------------------------------ */
/*  Domain resolution                                                  */
/* ------------------------------------------------------------------ */

/** Subsidiary → satellite-domain id: `metadata.beaconSlug` first, name fallback. */
export function resolveBeaconDomainId(
  domains: Domain[],
  sub: Pick<BeaconSubsidiary, 'slug' | 'name'>,
): string | null {
  const bySlug = domains.find((d) => d.metadata?.['beaconSlug'] === sub.slug);
  if (bySlug) return bySlug.id;
  const byName = domains.find((d) => d.name === sub.name);
  return byName?.id ?? null;
}

/* ------------------------------------------------------------------ */
/*  Hooks                                                              */
/* ------------------------------------------------------------------ */

export type AgentDataSource = 'live' | 'fixture';

export interface BeaconAgentsResult {
  agents: AgentRow[];
  source: AgentDataSource;
}

/** Agent fleet for one subsidiary's Operations tab (spec §13.5 `useAgents`). */
export function useBeaconAgents(sub: BeaconSubsidiary): BeaconAgentsResult {
  const workspace = useWorkspace();
  const domainId = resolveBeaconDomainId(workspace.domains, sub);

  const query = useApiQuery<AgentFleetRowDto[]>(
    ['agents', 'list', domainId ?? 'unresolved'],
    () => getAgents(domainId as string),
    // retry: false — degraded mode settles on the fixture fallback
    // immediately rather than spinning through backoff (cascadeData idiom).
    { enabled: domainId !== null, retry: false },
  );

  const live = query.data;
  const agents = useMemo(
    () => (live ? liveAgentRows(live) : sub.agents.map(fixtureToAgentRow)),
    [live, sub],
  );
  return { agents, source: live ? 'live' : 'fixture' };
}

/**
 * Click-through detail (spec §7.1 `GET /agents/{idOrKey}`): the ONLY place
 * `deploymentCriteria` travels — the render rule keeps it off the row line
 * ([AGENT-PROVENANCE]); the expansion panel fetches it on demand. Fetched
 * only in live mode (`enabled`); fixture mode reads the fixture field.
 */
export function useBeaconAgentDetail(
  sub: BeaconSubsidiary,
  agentId: string,
  enabled: boolean,
): AgentDetailDto | null {
  const workspace = useWorkspace();
  const domainId = resolveBeaconDomainId(workspace.domains, sub);

  const query = useApiQuery<AgentDetailDto>(
    ['agents', 'detail', domainId ?? 'unresolved', agentId],
    () => getAgentByIdOrKey(agentId, domainId as string),
    { enabled: enabled && domainId !== null && agentId.length > 0, retry: false },
  );
  return query.data ?? null;
}

/**
 * §13.1 fleet signals for the stat strip — "Agents active" ← `agentsTotal`
 * (the fixture card counts the whole non-retired fleet; renaming it is a
 * flagged post-swap UI question), "Agents in intervention" ←
 * `agentsInterventionRequired`. `null` while loading/absent — the caller
 * falls back to fixture-derived counts so the strip never renders blank.
 */
export function useBeaconAgentSignals(sub: BeaconSubsidiary): AgentSignalsDto | null {
  const workspace = useWorkspace();
  const domainId = resolveBeaconDomainId(workspace.domains, sub);

  const query = useApiQuery<AgentSignalsDto>(
    ['agents', 'signals', domainId ?? 'unresolved'],
    () => getAgentSignals(domainId as string),
    { enabled: domainId !== null, retry: false },
  );
  return query.data ?? null;
}
