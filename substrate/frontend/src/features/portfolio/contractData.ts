import { useMemo } from 'react';
import { getContractByKey } from '@/api/endpoints';
import type { ContractDetailResponse } from '@/api/endpoints';
import { useApiQuery } from '@/shared/hooks/useApi';
import { getContract as getFixtureContract } from '@/fixtures/beacon';
import type { BeaconContract } from '@/fixtures/beacon';

/**
 * Contract read-model seam — the Phase 3a live swap of the contract slice
 * of the `BEACON_PORTFOLIO` fixture seam (engine-spec contracts.md §11.3,
 * ADR-002, port-map §5 S5).
 *
 * THE SWAP SWITCH, documented (same contract as the Phase-2 cascade seam in
 * ./cascadeData.ts): the contract detail surface renders LIVE data whenever
 * `GET /api/v1/contracts/{idOrKey}` has produced it, and falls back to the
 * Beacon fixture contract whenever it has not — i.e. while the query is
 * still loading OR when it failed (backend absent / degraded). There is no
 * build flag: a reachable backend wins automatically; an absent backend
 * leaves the demo rendering deterministically from fixtures. Fixture data
 * is consumed ONLY inside this module — components import the hook from
 * here, never `getContract` directly — so the eventual route+directory
 * excision of the fixtures (port-map trap #9) touches only this seam.
 *
 * Read-only by design: no mutation hooks exist here. Review/Decline UI
 * affordances stay inert (queue #28 — the respond/decline endpoints are
 * operator/MCP-only until Mike's product-design session).
 * // EXTENSION POINT(queue-28)
 */

/* ------------------------------------------------------------------ */
/*  Adapter — ContractDetailResponse → BeaconContract (spec §7.3)      */
/* ------------------------------------------------------------------ */

/**
 * Thin adapter, exactly the §7.3 mapping ("the S5 swap is an adapter, not
 * a redesign"):
 *   - `id ← contractKey` (route ids and click-throughs resolve by key);
 *   - `parties[].commitments` (string[]) ← `commitments[].text` in the
 *     wire's sequenceOrder serialization;
 *   - `enforcement.monitor/monitorMechanism` ← VERBATIM text fields (the
 *     font-mono mechanism panel renders unchanged);
 *   - `enforcement.declineTriggers` (string[]) ← trigger `text` (armed
 *     standing is a superset field, unrendered in v1);
 *   - `estimatedValue ← estimatedValue`, with null mapped to an ABSENT
 *     key so the view's `contract.estimatedValue &&` aside-hiding and the
 *     fixture deep-equality both hold (§14.1 vector 2: null serialization
 *     preserved, UI hides the value aside);
 *   - `linkedSubsidiarySlugs ← linkedEntities[].slug` for entries of type
 *     SUBSIDIARY_SLUG only;
 *   - `proposedAt ←` date part of the proposedAt instant.
 * Superset fields (status, monitors, breachSummary, responses, …) are
 * deliberately dropped — no current surface renders them.
 */
export function adaptContractDetail(dto: ContractDetailResponse): BeaconContract {
  const contract: BeaconContract = {
    id: dto.contractKey,
    type: dto.type,
    title: dto.title,
    context: dto.context,
    parties: dto.parties.map((party) => ({
      partyLabel: party.partyLabel,
      commitments: party.commitments.map((commitment) => commitment.text),
    })),
    enforcement: {
      monitor: dto.enforcement.monitor,
      monitorMechanism: dto.enforcement.monitorMechanism,
      declineTriggers: dto.enforcement.declineTriggers.map((trigger) => trigger.text),
    },
    linkedSubsidiarySlugs: dto.linkedEntities.flatMap((entity) =>
      entity.entityType === 'SUBSIDIARY_SLUG' && entity.slug ? [entity.slug] : [],
    ),
    proposedAt: dto.proposedAt.split('T')[0],
  };
  if (dto.estimatedValue !== null) {
    contract.estimatedValue = dto.estimatedValue;
  }
  return contract;
}

/* ------------------------------------------------------------------ */
/*  Hook                                                               */
/* ------------------------------------------------------------------ */

export type ContractDetailStatus = 'live' | 'fixture' | 'loading' | 'not-found';

export interface BeaconContractResult {
  contract: BeaconContract | null;
  status: ContractDetailStatus;
}

/**
 * Single contract for the detail view (spec §11.3).
 *
 * No domain binding needed: `{idOrKey}` resolves UUID first, then
 * `(current engagement domain, contract_key)` on the backend (spec §7).
 *
 * Resolution order: live detail when the API produced it; else the fixture
 * contract with the same id (degraded/demo mode — fixture ids ARE the
 * seeded contractKeys, so the pair stays consistent); else `loading` while
 * the query is in flight; else `not-found` (the existing 404 fallback
 * panel, spec §11.3).
 */
export function useBeaconContract(idOrKey: string): BeaconContractResult {
  const query = useApiQuery<ContractDetailResponse>(
    ['contracts', 'detail', idOrKey],
    () => getContractByKey(idOrKey),
    // retry: false — degraded mode should settle on the fixture fallback
    // immediately rather than spin through backoff against a dead socket.
    { enabled: idOrKey.length > 0, retry: false },
  );

  const live = query.data;
  const contract = useMemo(
    () => (live ? adaptContractDetail(live) : getFixtureContract(idOrKey) ?? null),
    [live, idOrKey],
  );

  let status: ContractDetailStatus;
  if (live) {
    status = 'live';
  } else if (contract) {
    status = 'fixture';
  } else if (idOrKey.length > 0 && query.isPending && !query.isError) {
    status = 'loading';
  } else {
    status = 'not-found';
  }
  return { contract, status };
}
