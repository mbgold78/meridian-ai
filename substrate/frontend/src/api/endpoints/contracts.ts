import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';

/**
 * Contract read endpoints — engine-spec contracts.md §7.1 (read model only).
 *
 * The detail DTO deliberately SUPERSETS the Beacon `BeaconContract` fixture
 * shape (spec §7.3, ADR-002 — "the frontend swap is an adapter, not a
 * redesign"). The dashboard consumes it through the thin adapter in
 * features/portfolio/contractData.ts; superset fields (status, monitors,
 * breachSummary, party responses, …) are carried for future surfaces but
 * dropped by the v1 adapter.
 *
 * Write endpoints (create/patch/respond/decline/attest/…, spec §7.2) are
 * deliberately NOT wired here: Review/Decline UI affordances stay inert
 * (queue #28 — endpoints are operator/MCP-only until Mike's product-design
 * session). // EXTENSION POINT(queue-28)
 */

/** `contract_type` allowlist values (spec §1, ADR D15). */
export type ContractTypeWire =
  | 'cross-portfolio-opportunity'
  | 'federation-invitation';

/**
 * `ContractStatus` wire form (spec §2.1). WITHDRAWN/CONCLUDED are
 * schema-reserved but transition-UNREACHABLE in v1 (ADR D7) — typed so the
 * union matches the enum, never expected on the wire.
 * Decline is a legitimate terminal resolution, never an error.
 */
export type ContractStatusWire =
  | 'PROPOSED'
  | 'ACTIVE'
  | 'DECLINED'
  | 'WITHDRAWN'
  | 'CONCLUDED';

/** `ContractPartyType` wire form (spec §2.1). */
export type ContractPartyTypeWire = 'DOMAIN' | 'ORG_UNIT' | 'COMBINED' | 'EXTERNAL';

/** `PartyResponse` wire form (spec §2.1). */
export type ContractPartyResponseWire = 'ACCEPTED' | 'DECLINED';

/** `CommitmentComplianceState` wire form (spec §2.1/§2.3). */
export type CommitmentComplianceStateWire =
  | 'UNEVALUATED'
  | 'COMPLIANT'
  | 'AT_RISK'
  | 'BREACHED'
  | 'WAIVED';

/** `MonitorRuleType` wire form (spec §2.1). */
export type ContractMonitorRuleTypeWire =
  | 'ATTESTATION_CADENCE'
  | 'METRIC_THRESHOLD'
  | 'MCP_PROBE'
  | 'UNSTRUCTURED';

/** `MonitorStatus` wire form (spec §2.1/§2.5). */
export type ContractMonitorStatusWire = 'ACTIVE' | 'SUSPENDED';

/**
 * `MonitorRunOutcome` wire form (spec §2.1). INDETERMINATE/NOT_DUE/ERROR
 * never open or refresh a breach (ADR D10) — render as needs-input, never
 * breach-colored.
 */
export type ContractMonitorRunOutcomeWire =
  | 'COMPLIANT'
  | 'BREACH_DETECTED'
  | 'INDETERMINATE'
  | 'NOT_DUE'
  | 'ERROR';

/** `linked_entity_type` values (V5 DDL, spec §3 table 6). */
export type ContractLinkedEntityTypeWire =
  | 'DOMAIN'
  | 'SYSTEM'
  | 'PROCESS'
  | 'CASCADE'
  | 'AGENT_REGISTRATION'
  | 'SUBSIDIARY_SLUG';

/** Entity back-reference (party boundEntities + contract linkedEntities, §7.3). */
export interface ContractEntityRefDto {
  entityType: ContractLinkedEntityTypeWire;
  entityId: string | null;
  slug: string | null;
  label: string | null;
}

/** parties[].commitments[] element — serialized in sequenceOrder (§7.3). */
export interface ContractCommitmentDto {
  id: string;
  text: string;
  dueBy: string | null;
  cadence: string | null;
  complianceState: CommitmentComplianceStateWire;
  lastEvaluatedAt: string | null;
}

/** parties[].response — null until the party responds (§7.3). */
export interface ContractPartyResponseDto {
  response: ContractPartyResponseWire;
  respondedBy: string;
  respondedAt: string;
  declineTriggerId: string | null;
  declineReasoning: string | null;
}

/** parties[] element (§7.3). */
export interface ContractPartyDto {
  id: string;
  partyLabel: string;
  partyType: ContractPartyTypeWire;
  boundEntities: ContractEntityRefDto[];
  responseRequired: boolean;
  response: ContractPartyResponseDto | null;
  commitments: ContractCommitmentDto[];
}

/** enforcement.declineTriggers[] element (§7.3) — armed standing per §2.6. */
export interface ContractDeclineTriggerDto {
  id: string;
  text: string;
  armed: boolean;
  invocableByPartyId: string | null;
}

/** enforcement block — monitor/monitorMechanism are VERBATIM fixture text (§7.3). */
export interface ContractEnforcementDto {
  monitor: string;
  monitorMechanism: string;
  declineTriggers: ContractDeclineTriggerDto[];
}

/** monitors[] element (§7.3). */
export interface ContractMonitorDto {
  id: string;
  name: string;
  ruleType: ContractMonitorRuleTypeWire;
  status: ContractMonitorStatusWire;
  cadence: string;
  graceDays: number;
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastOutcome: ContractMonitorRunOutcomeWire | null;
}

/** breachSummary block (§7.3). */
export interface ContractBreachSummaryDto {
  open: number;
  bySeverity: Record<string, number>;
}

/** GET /contracts/{idOrKey} (spec §7.3 ContractDetailResponse). */
export interface ContractDetailResponse {
  id: string;
  contractKey: string;
  type: ContractTypeWire;
  title: string;
  context: string;
  status: ContractStatusWire;
  /** ISO instant, e.g. "2026-04-01T00:00:00Z". */
  proposedAt: string;
  proposedBy: string | null;
  activatedAt: string | null;
  resolvedAt: string | null;
  /** Null serialization preserved — the UI hides the value aside (§14.1 vector 2). */
  estimatedValue: string | null;
  estimatedValueAmount: number | null;
  parties: ContractPartyDto[];
  enforcement: ContractEnforcementDto;
  linkedEntities: ContractEntityRefDto[];
  monitors: ContractMonitorDto[];
  breachSummary: ContractBreachSummaryDto;
  /**
   * Federation review-boundary JSON; unenforced when null (spec §15 #1 —
   * boundary semantics undocumented). // EXTENSION POINT(review-boundary)
   */
  reviewBoundary: Record<string, unknown> | null;
}

/** GET /contracts element (spec §7.1 ContractCardResponse). */
export interface ContractCardResponse {
  id: string;
  contractKey: string;
  type: ContractTypeWire;
  title: string;
  status: ContractStatusWire;
  proposedAt: string;
  estimatedValue: string | null;
  partyLabels: string[];
  linkedEntities: ContractEntityRefDto[];
  openBreachCount: number;
}

/** Optional GET /contracts filters (spec §7.1). */
export interface GetContractsParams {
  status?: ContractStatusWire;
  type?: ContractTypeWire;
}

export async function getContracts(
  domainId: string,
  params?: GetContractsParams,
): Promise<ApiResponse<ContractCardResponse[]>> {
  const { data } = await apiClient.get<ApiResponse<ContractCardResponse[]>>(
    '/contracts',
    { params: { domainId, ...params } },
  );
  return data;
}

/**
 * `{idOrKey}` resolves UUID first, then `(current engagement domain,
 * contract_key)` (spec §7) — `BeaconCrossOpportunity.contractId`
 * click-throughs resolve by contractKey (spec §11.3).
 */
export async function getContractByKey(
  idOrKey: string,
): Promise<ApiResponse<ContractDetailResponse>> {
  const { data } = await apiClient.get<ApiResponse<ContractDetailResponse>>(
    `/contracts/${idOrKey}`,
  );
  return data;
}
