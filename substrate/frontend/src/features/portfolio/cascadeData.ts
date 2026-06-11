import { useMemo } from 'react';
import {
  getCascadeActions,
  getCascadeById,
  getCascades,
} from '@/api/endpoints';
import type {
  CascadeActionDto,
  CascadeAttentionSignalWire,
  CascadeDetailDto,
  CascadeLevelDto,
  CascadeSummaryDto,
} from '@/api/endpoints';
import { useApiQuery } from '@/shared/hooks/useApi';
import { useWorkspace } from '@/shared/hooks/useWorkspace';
import { BEACON_CASCADES, getCascade as getFixtureCascade } from '@/fixtures/beacon';
import type { BeaconCascade, CascadeLevel } from '@/fixtures/beacon';

/**
 * Cascade read-model seam — the Phase 2 live swap of the cascade slice of
 * the `BEACON_PORTFOLIO` fixture seam (engine-spec cascade.md §11, ADR-001
 * D13, port-map §5 S5).
 *
 * THE SWAP SWITCH, documented: the portfolio surfaces render LIVE cascade
 * data whenever the read API has produced it, and fall back to the Beacon
 * fixture cascades whenever it has not — i.e. while the list/detail query
 * is still loading OR when it failed (backend absent / degraded). There is
 * no build flag: a reachable backend wins automatically; an absent backend
 * leaves the demo rendering deterministically from fixtures. Fixture data
 * is consumed ONLY inside this module — components import hooks from here,
 * never `BEACON_CASCADES`/`getCascade` directly — so the eventual
 * route+directory excision of the fixtures (port-map trap #9) touches only
 * this seam.
 *
 * Read-only by design: no mutation hooks exist here. Write affordances are
 * gated on the 409-and-refresh pattern (spec §11 item 5) and the queue-#24
 * leader-authoring session. // EXTENSION POINT(cascade-5)
 */

/* ------------------------------------------------------------------ */
/*  Adapters — API DTOs → Beacon view types (thin, per ADR-001 D13)    */
/* ------------------------------------------------------------------ */

/**
 * Grid-row shape for the "Cascades you've issued" grid — the
 * `CascadeSummary → grid row` adapter target (spec §11 item 1). Both the
 * live summaries and the fixture fallback normalize into this shape so
 * the card component has a single input type.
 */
export interface CascadeGridRow {
  id: string;
  originalCritique: string;
  /** ISO date or instant — formatted by the card. */
  originatedAt: string;
  currentState: BeaconCascade['currentState'];
  attentionSignal: CascadeAttentionSignalWire;
  /** Separate boolean overlay flag, never a signal value (spec §2). */
  overdue: boolean;
  levelCount: number;
  frontierDepth: number;
  lastActivityAt: string;
}

export function adaptCascadeSummary(dto: CascadeSummaryDto): CascadeGridRow {
  return {
    id: dto.id,
    originalCritique: dto.originalCritique,
    originatedAt: dto.originatedAt,
    currentState: dto.currentState,
    attentionSignal: dto.attentionSignal,
    overdue: dto.overdue,
    levelCount: dto.levelCount,
    frontierDepth: dto.frontierDepth,
    lastActivityAt: dto.lastActivityAt,
  };
}

export function adaptCascadeLevel(dto: CascadeLevelDto): CascadeLevel {
  return {
    level: dto.level,
    ownerLabel: dto.ownerLabel,
    state: dto.state, // kebab-case wire states match the view union 1:1 (D13)
    refinedCritique: dto.refinedCritique ?? undefined,
    declineReasoning: dto.declineReasoning ?? undefined,
    // `timestamp` is the backend's fixture-compat acted/activated date
    // (spec §11 item 1); fall back defensively for robustness only.
    timestamp: dto.timestamp ?? dto.actedAt ?? dto.activatedAt,
  };
}

export function adaptCascadeDetail(dto: CascadeDetailDto): BeaconCascade {
  return {
    id: dto.id,
    originatedBy: dto.originatedBy,
    originatedAt: dto.originatedAt,
    originalCritique: dto.originalCritique,
    acceptanceCriteria: dto.acceptanceCriteria,
    currentState: dto.currentState,
    // `affectedSubsidiarySlug` is intentionally not populated: the live DTO
    // carries affectedDomainId/affectedDomainName instead (a Beacon
    // subsidiary IS a Domain, spec §11 item 3) and no current surface
    // renders the slug.
    levels: dto.levels.map(adaptCascadeLevel),
  };
}

/**
 * Fixture cascade → grid row, deriving the attention signal exactly as the
 * engine projects it (spec §2): frontier declined → decline-pending;
 * frontier absorbed → absorbed; frontier looking OR bottom-acted-level
 * cascaded ("response still out") → escalating. Fixtures never carry
 * overdue (demo seeds run with SLA disabled, spec §11.4).
 */
export function fixtureToGridRow(cascade: BeaconCascade): CascadeGridRow {
  const bottom = cascade.levels[cascade.levels.length - 1];
  return {
    id: cascade.id,
    originalCritique: cascade.originalCritique,
    originatedAt: cascade.originatedAt,
    currentState: cascade.currentState,
    attentionSignal: fixtureAttentionSignal(cascade, bottom),
    overdue: false,
    levelCount: cascade.levels.length,
    frontierDepth: bottom.level,
    lastActivityAt: bottom.timestamp,
  };
}

function fixtureAttentionSignal(
  cascade: BeaconCascade,
  bottom: CascadeLevel,
): CascadeAttentionSignalWire {
  if (cascade.currentState === 'resolved-looks-good') return 'resolved-looks-good';
  if (cascade.currentState === 'resolved-declined') return 'resolved-declined';
  switch (bottom.state) {
    case 'declined':
      return 'decline-pending';
    case 'absorbed':
      return 'absorbed';
    case 'looking':
    case 'cascaded':
      return 'escalating';
  }
}

/**
 * Operator attention order for the issued-cascades grid (spec §11 item 4):
 * decline-pending first, then overdue, then escalating by lastActivityAt
 * desc. Returns a new array; input is untouched.
 */
export function attentionOrder(rows: CascadeGridRow[]): CascadeGridRow[] {
  const rank = (row: CascadeGridRow): number => {
    if (row.attentionSignal === 'decline-pending') return 0;
    if (row.overdue) return 1;
    return 2;
  };
  return [...rows].sort((a, b) => {
    const byRank = rank(a) - rank(b);
    if (byRank !== 0) return byRank;
    return b.lastActivityAt.localeCompare(a.lastActivityAt);
  });
}

/* ------------------------------------------------------------------ */
/*  Hooks                                                              */
/* ------------------------------------------------------------------ */

export type CascadeDataSource = 'live' | 'fixture';

export interface BeaconCascadesResult {
  rows: CascadeGridRow[];
  source: CascadeDataSource;
}

/**
 * Cascades for the Operating Partner grid (spec §11 `useCascades`).
 *
 * Domain binding: the Beacon dashboards bind to the demo-seeded domain when
 * one exists — `CascadeDemoSeeder` seeds the isDemo domain only (spec
 * §11.4) — otherwise the workspace-resolved domain. Until a domain id is
 * known (domains still loading, fetch failed, or empty database) the query
 * stays disabled and the fixture fallback renders.
 */
export function useBeaconCascades(): BeaconCascadesResult {
  const workspace = useWorkspace();
  const domainId = workspace.domains.find((d) => d.demo)?.id ?? workspace.domainId;

  const query = useApiQuery<CascadeSummaryDto[]>(
    ['cascades', 'list', domainId ?? 'unresolved'],
    () => getCascades(domainId as string),
    // retry: false — degraded mode should settle on the fixture fallback
    // immediately rather than spin through backoff against a dead socket.
    { enabled: domainId !== null, retry: false },
  );

  const live = query.data;
  const rows = useMemo(
    () => (live ? live.map(adaptCascadeSummary) : BEACON_CASCADES.map(fixtureToGridRow)),
    [live],
  );
  return { rows, source: live ? 'live' : 'fixture' };
}

export type CascadeDetailStatus = 'live' | 'fixture' | 'loading' | 'not-found';

export interface BeaconCascadeResult {
  cascade: BeaconCascade | null;
  status: CascadeDetailStatus;
}

/**
 * Single cascade for the detail view (spec §11 `useCascade`).
 *
 * Resolution order: live detail when the API produced it; else the fixture
 * cascade with the same id (degraded/demo mode — fixture ids only ever
 * appear in fixture-rendered grids, so the pair stays consistent); else
 * `loading` while the query is in flight; else `not-found`.
 */
export function useBeaconCascade(id: string): BeaconCascadeResult {
  const query = useApiQuery<CascadeDetailDto>(
    ['cascades', 'detail', id],
    () => getCascadeById(id),
    { enabled: id.length > 0, retry: false },
  );

  const live = query.data;
  const cascade = useMemo(
    () => (live ? adaptCascadeDetail(live) : getFixtureCascade(id) ?? null),
    [live, id],
  );

  let status: CascadeDetailStatus;
  if (live) {
    status = 'live';
  } else if (cascade) {
    status = 'fixture';
  } else if (id.length > 0 && query.isPending && !query.isError) {
    status = 'loading';
  } else {
    status = 'not-found';
  }
  return { cascade, status };
}

/**
 * Append-only action timeline for a cascade — the originator's
 * single-throat-to-choke read (spec §6.1). Additive surface (ADR-001 D13):
 * only fetched in live mode (`enabled`), because fixtures predate the
 * action log; callers hide the section when undefined/empty.
 */
export function useBeaconCascadeActions(
  id: string,
  enabled: boolean,
): CascadeActionDto[] | undefined {
  const query = useApiQuery<CascadeActionDto[]>(
    ['cascades', 'actions', id],
    () => getCascadeActions(id),
    { enabled: enabled && id.length > 0, retry: false },
  );
  return query.data;
}
