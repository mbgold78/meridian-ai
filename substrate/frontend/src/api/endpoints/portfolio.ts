import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';

/**
 * D5 portfolio read endpoints — engine-spec diagnostic.md §12/§12.7
 * (ADR-004 D13). Severs fixture stat seam S6: `lifecycleStallCount`,
 * `coordinationPatternsThisWeek`, `pendingFederationInvitations`,
 * `progressPct` and `surfacedAt` are engine-computed at read time; nothing
 * here is stamped.
 *
 * Absent-vs-zero is STRUCTURAL on this wire (spec non-negotiable #4): an
 * absent agent registry arrives as `agents.available=false` with `null`
 * counts (never 0); domains with zero phase states are the separate
 * `unphased` count; `aiReadiness` is always `null` in v1 — omitted, not
 * invented, with the omission named in `computedFrom.aiReadiness`.
 * Renderers must keep those distinctions visible (em-dash, never a zero).
 */

/** Per-source sub-counts of the §12.2 delegated coordination number. */
export interface PortfolioCoordinationBreakdownDto {
  contractEvents: number;
  cascadeActions: number;
}

/** D3 agent stats — `available:false` ⇒ all counts null (absent ≠ zero). */
export interface PortfolioAgentStatsDto {
  available: boolean;
  total: number | null;
  live: number | null;
  shadow: number | null;
  paused: number | null;
  interventionRequired: number | null;
}

/** §12.6 rollups — operator default filter + client-surface variant. */
export interface PortfolioFindingRollupDto {
  bySeverity: Record<string, number>;
  aiGenerated: number;
  operatorAuthored: number;
  clientSurfaceBySeverity: Record<string, number>;
}

/** One portfolio subsidiary (= one Domain; ADR-001 D13). */
export interface PortfolioSubsidiaryCardDto {
  domainId: string;
  name: string;
  /** Fixture kebab vocabulary (`design` … `automate`); null = no active sequenced phase. */
  lifecyclePhase: string | null;
  /** Zero PhaseState rows — the needs-input signal, never folded into stalls. */
  phaseStatesUninitialized: boolean;
  lifecycleStalled: boolean;
  findingCounts: PortfolioFindingRollupDto;
  agents: PortfolioAgentStatsDto;
  pendingFederationInvitations: number;
  /** Always null in v1 — omitted, not invented (spec §15 #15). */
  aiReadiness: string | null;
}

/** §12.7 coordination-signal strip block. */
export interface PortfolioCoordinationSignalsDto {
  lifecycleStallCount: number;
  coordinationPatternsThisWeek: number;
  coordinationBreakdown: PortfolioCoordinationBreakdownDto;
  declinePendingCascades: number;
  agentsAvailable: boolean;
  /** null when the registry is absent — never rendered as 0. */
  agentsInIntervention: number | null;
  needsInputByKind: Record<string, number>;
  pendingFederationInvitations: number;
}

/** §12 honesty meta block — what every number was computed from. */
export interface PortfolioComputedFromDto {
  computedAt: string;
  stallThresholdDays: number;
  coordinationWindowDays: number;
  demoDomainsIncluded: boolean;
  domainsConsidered: number;
  agentRegistryAvailable: boolean;
  aiReadiness: string;
  sources: Record<string, string>;
}

/** GET /api/v1/portfolio body (spec §12.7). */
export interface PortfolioViewDto {
  companyName: string;
  subsidiaries: PortfolioSubsidiaryCardDto[];
  coordinationSignals: PortfolioCoordinationSignalsDto;
  /** Contract cards of type cross-portfolio-opportunity (contract read model). */
  crossPortfolioOpportunities: unknown[];
  /** SEPARATE from the stall count — §12.1, never folded in. */
  unphased: number;
  computedFrom: PortfolioComputedFromDto;
}

/** GET /api/v1/portfolio/subsidiaries/{domainId} initiative row (§12.5 — V8). */
export interface PortfolioInitiativeDto {
  id: string;
  planId: string;
  name: string;
  description: string | null;
  initiativeType: string;
  status: string;
  wave: number | null;
  ownerName: string | null;
  estimatedMonths: number | null;
  complexity: string | null;
  annualSavingsUsd: number | null;
  /** RENDERED value — COMPLETE ⇒ 100 is the only computed rule. */
  progressPct: number;
  progressNote: string | null;
  /** Staleness is surfaced, never hidden. */
  progressUpdatedAt: string | null;
}

/** Client-surfaced finding with the §12.4 surfacedAt projection. */
export interface PortfolioFindingRowDto {
  id: string;
  severity: string | null;
  findingType: string | null;
  title: string;
  description: string;
  aiGenerated: boolean;
  curationStatus: string | null;
  surfacedAt: string | null;
}

/** GET /api/v1/portfolio/subsidiaries/{domainId} body (spec §12.7). */
export interface PortfolioSubsidiaryDetailDto {
  domainId: string;
  name: string;
  description: string | null;
  lifecyclePhase: string | null;
  phaseStatesUninitialized: boolean;
  lifecycleStalled: boolean;
  aiReadiness: string | null;
  capabilities: unknown[];
  processes: unknown[];
  systems: unknown[];
  findings: PortfolioFindingRowDto[];
  findingCounts: PortfolioFindingRollupDto;
  agents: PortfolioAgentStatsDto;
  /** null (absent) when the registry bean is missing — never []. */
  agentFleet: unknown[] | null;
  initiatives: PortfolioInitiativeDto[];
  pendingFederationInvitations: number;
  attention: { cascades: unknown[]; agents: unknown[] | null };
  computedFrom: PortfolioComputedFromDto;
}

export async function getPortfolio(): Promise<ApiResponse<PortfolioViewDto>> {
  const { data } = await apiClient.get<ApiResponse<PortfolioViewDto>>('/portfolio');
  return data;
}

export async function getPortfolioSubsidiary(
  domainId: string,
): Promise<ApiResponse<PortfolioSubsidiaryDetailDto>> {
  const { data } = await apiClient.get<ApiResponse<PortfolioSubsidiaryDetailDto>>(
    `/portfolio/subsidiaries/${domainId}`,
  );
  return data;
}
