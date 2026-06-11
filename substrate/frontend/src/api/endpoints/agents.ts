import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';

/**
 * Agent registry read endpoints — engine-spec agents.md §7.1/§7.3/§13.1
 * (Phase 6a read surface only).
 *
 * Wire vocabulary is the FIXTURE vocabulary: `status` serializes the §5.2
 * pinned-precedence projection as `live | shadow | paused |
 * intervention-required` plus the additive `retired` (Mike-flagged, ADR-003
 * D4) which is EXCLUDED from fleet lists by default — the OperationsTab
 * `Record<AgentStatus,…>` pill map crashes on unknown values, so the adapter
 * in `features/portfolio/agentData.ts` additionally drops `retired` rows
 * defensively.
 *
 * Write endpoints (deploy/pause/kill/…) are deliberately NOT wired here: no
 * Ops verb affordance ships ahead of its API — the §7.2 verb set + the
 * 409-refresh pattern land with 6b (spec §13.5).
 */

/** §5.2 wire-status union = fixture `AgentStatus` + additive `retired`. */
export type AgentWireStatus =
  | 'live'
  | 'shadow'
  | 'paused'
  | 'intervention-required'
  | 'retired';

/** GET /agents element (spec §7.3 AgentFleetRow — BeaconAgent superset). */
export interface AgentFleetRowDto {
  /** = agent_key (fixture parity, e.g. "a-pre-visit"). */
  id: string;
  registrationId: string;
  domainId: string;
  name: string;
  role: string;
  status: AgentWireStatus;
  /** Machine state — the OTHER dimension (additive): live|shadow|paused|retired. */
  operationalMode: string;
  /** DERIVED — never blank, never invented (spec §7.3). */
  recentActivity: string;
  /** NULLABLE — latest 'adherence' metric sample; null until data exists. */
  adherence: number | null;
  capabilityId: string | null;
  deployedBy: string;
  /** ISO instant, e.g. "2025-09-14T00:00:00Z". */
  deployedAt: string;
  /** Ordered oversight labels, position asc. */
  oversightChain: string[];
  executionLocus: string;
  supervisoryModel: string;
  openInterventions: number;
  eventSeq: number;
}

/** GET /agents/{idOrKey} (spec §7.3 AgentDetail = fleet row + click-through mandate). */
export interface AgentDetailDto extends AgentFleetRowDto {
  /** CLICK-THROUGH ONLY — never rendered on the row line ([AGENT-PROVENANCE]). */
  deploymentCriteria: string;
  criteriaDigest: string;
  criteriaGates: Array<{
    gateText: string;
    ruleType: 'structured' | 'unstructured';
    predicate: Record<string, unknown> | null;
    cadence: string | null;
    standing: string;
    lastEvaluatedAt: string | null;
  }>;
  notes: string | null;
  readOnly: boolean;
  shadowEnteredAt: string | null;
  promotionRequestedAt: string | null;
  retired: { at: string; reason: string; byLabel: string } | null;
  lineage: { predecessorRegistrationId: string | null };
}

/** GET /agents/signals (spec §13.1 — explicit zeros ALWAYS present). */
export interface AgentSignalsDto {
  agentsTotal: number;
  agentsLive: number;
  agentsShadow: number;
  agentsPaused: number;
  agentsInterventionRequired: number;
  interventions: { open: number; oldestOpenedAt: string | null };
  unconfirmedControls: Array<{
    agentKey: string;
    verb: string;
    requestedAt: string | null;
    confirmationState: string;
  }>;
  controlViolations: Array<{ agentKey: string; kind: string; lastSeenAt: string | null }>;
  liveness: { stale: number; neverSeen: number; attestOnly: number };
  promotionsPending: Array<{ agentKey: string; requestedAt: string | null }>;
  budgetsExhausted: Array<{ agentKey: string; budgetKind: string }>;
  gateCoverage: { structured: number; unstructured: number; unevaluated: number };
}

export async function getAgents(
  domainId: string,
  params?: { status?: AgentWireStatus; includeRetired?: boolean },
): Promise<ApiResponse<AgentFleetRowDto[]>> {
  const { data } = await apiClient.get<ApiResponse<AgentFleetRowDto[]>>('/agents', {
    params: { domainId, ...params },
  });
  return data;
}

export async function getAgentByIdOrKey(
  idOrKey: string,
  domainId?: string,
): Promise<ApiResponse<AgentDetailDto>> {
  const { data } = await apiClient.get<ApiResponse<AgentDetailDto>>(`/agents/${idOrKey}`, {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}

export async function getAgentSignals(
  domainId: string,
): Promise<ApiResponse<AgentSignalsDto>> {
  const { data } = await apiClient.get<ApiResponse<AgentSignalsDto>>('/agents/signals', {
    params: { domainId },
  });
  return data;
}
