import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';

/**
 * Cascade read endpoints — engine-spec cascade.md §6.1 (read model only).
 *
 * Wire vocabulary is the fixture vocabulary: every enum serializes
 * kebab-case via @JsonValue on the backend (ADR-001 D13). The DTO
 * `levels[]` shape is deliberately 1:1 with the Beacon `CascadeLevel`
 * view type so the dashboard swap needs only a thin adapter
 * (see features/portfolio/cascadeData.ts).
 *
 * Write endpoints (cascade/absorb/decline/recascade/resolve, drafts) are
 * deliberately NOT wired here: no write affordance ships before the
 * 409-and-refresh pattern (spec §11 item 5) and the queue-#24 leader
 * authoring session. // EXTENSION POINT(cascade-5)
 */

/** `CascadeState` wire form (spec §2). Decline is never an error. */
export type CascadeStateWire =
  | 'in-flight'
  | 'resolved-looks-good'
  | 'resolved-declined';

/** `CascadeLevelState` wire form (spec §2). */
export type CascadeLevelStateWire =
  | 'looking'
  | 'absorbed'
  | 'cascaded'
  | 'declined';

/** `CascadeAttentionSignal` wire form (spec §2). */
export type CascadeAttentionSignalWire =
  | 'decline-pending'
  | 'escalating'
  | 'absorbed'
  | 'resolved-looks-good'
  | 'resolved-declined';

/** `CascadeActionType` wire form (spec §2). */
export type CascadeActionTypeWire =
  | 'issue'
  | 'cascade'
  | 'absorb'
  | 'decline'
  | 'recascade'
  | 'resolve-looks-good'
  | 'resolve-declined';

/** GET /cascades element (spec §6.1 CascadeSummary). */
export interface CascadeSummaryDto {
  id: string;
  originatedBy: string;
  originatedByMemberId: string | null;
  /** ISO instant, e.g. "2026-04-08T00:00:00Z". */
  originatedAt: string;
  originalCritique: string;
  acceptanceCriteria: string;
  /** Reserved, unrendered (queue #22). */
  summary: string | null;
  currentState: CascadeStateWire;
  attentionSignal: CascadeAttentionSignalWire;
  overdue: boolean;
  levelCount: number;
  frontierDepth: number;
  frontierState: CascadeLevelStateWire;
  frontierOwnerLabel: string;
  lastActivityAt: string;
  affectedDomainId: string | null;
  affectedDomainName: string | null;
  cascadeGroupId: string | null;
  predecessorCascadeId: string | null;
  chainSeq: number;
}

/** CascadeDetail.levels[] element — 1:1 with the Beacon CascadeLevel view type. */
export interface CascadeLevelDto {
  level: number;
  ownerLabel: string;
  ownerMemberId: string | null;
  state: CascadeLevelStateWire;
  round: number;
  refinedCritique: string | null;
  declineReasoning: string | null;
  activatedAt: string;
  actedAt: string | null;
  /** Acted/activated ISO date — fixture-compat field (spec §11 item 1). */
  timestamp: string;
  responseDeadline: string | null;
  overdue: boolean;
}

/** CascadeDetail.resolution — null while in-flight. */
export interface CascadeResolutionDto {
  resolvedAt: string;
  resolvedByLabel: string;
  note: string;
  acceptedDeclineActionId: string | null;
}

/** ADR-001 D2 — read-only sibling decline advisory. */
export interface SiblingDeclineAdvisoryDto {
  decliningCascadeId: string;
  depth: number;
  declinedAt: string;
  reasoningExcerpt: string;
}

/** GET /cascades/{id} (spec §6.1 CascadeDetail = CascadeSummary + …). */
export interface CascadeDetailDto extends CascadeSummaryDto {
  levels: CascadeLevelDto[];
  resolution: CascadeResolutionDto | null;
  siblingDeclineAdvisories: SiblingDeclineAdvisoryDto[];
}

/** GET /cascades/{id}/actions element (spec §6.1 CascadeAction). */
export interface CascadeActionDto {
  id: string;
  chainSeq: number;
  depth: number;
  round: number;
  action: CascadeActionTypeWire;
  actorLabel: string;
  actorMemberId: string | null;
  refinedCritique: string | null;
  declineReasoning: string | null;
  resolutionNote: string | null;
  nextOwnerLabel: string | null;
  actedAt: string;
}

/** Optional GET /cascades filters (spec §6.1). */
export interface GetCascadesParams {
  state?: CascadeStateWire;
  signal?: CascadeAttentionSignalWire;
  originOwnerId?: string;
  affectedDomainId?: string;
  groupId?: string;
}

export async function getCascades(
  domainId: string,
  params?: GetCascadesParams,
): Promise<ApiResponse<CascadeSummaryDto[]>> {
  const { data } = await apiClient.get<ApiResponse<CascadeSummaryDto[]>>('/cascades', {
    params: { domainId, ...params },
  });
  return data;
}

export async function getCascadeById(id: string): Promise<ApiResponse<CascadeDetailDto>> {
  const { data } = await apiClient.get<ApiResponse<CascadeDetailDto>>(`/cascades/${id}`);
  return data;
}

export async function getCascadeActions(
  id: string,
): Promise<ApiResponse<CascadeActionDto[]>> {
  const { data } = await apiClient.get<ApiResponse<CascadeActionDto[]>>(
    `/cascades/${id}/actions`,
  );
  return data;
}
