import { describe, expect, it } from 'vitest';
import { adaptContractDetail } from '@/features/portfolio/contractData';
import type {
  ContractDetailResponse,
  ContractEntityRefDto,
} from '@/api/endpoints';
import { getContract } from '@/fixtures/beacon';
import type { BeaconContract } from '@/fixtures/beacon';

/**
 * Adapter pins for the contract read-model swap (engine-spec contracts.md
 * §7.3 + §14.3, ADR-002): `ContractDetailResponse → BeaconContract` output
 * deep-equals the fixture objects for BOTH §14.1 vectors.
 *
 * FIXTURE SOURCE OF TRUTH:
 *   C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts
 *   (the FILE, not prose — known narrative-drift trap, port-map appendix #9).
 *   All vector strings below were copied from that file on 2026-06-10 and
 *   verified byte-identical to the ported copy at
 *   src/fixtures/beacon/contracts.ts on the same date.
 *
 * Every test asserts against BOTH a literal expectation copied from the
 * fixture file AND the imported fixture object, so drift in either the
 * adapter or the ported fixture fails loudly.
 */

function subsidiaryLink(slug: string, label: string): ContractEntityRefDto {
  // entityId is null for SUBSIDIARY_SLUG links (spec §7.3 example); the
  // adapter reads only entityType + slug.
  return { entityType: 'SUBSIDIARY_SLUG', entityId: null, slug, label };
}

/* ------------------------------------------------------------------ */
/*  Vector 1 — co-tribal-knowledge-depopulation (§14.1)                */
/* ------------------------------------------------------------------ */

const VECTOR_1: ContractDetailResponse = {
  id: '00000000-0000-0000-0000-000000000c01',
  contractKey: 'co-tribal-knowledge-depopulation',
  type: 'cross-portfolio-opportunity',
  title: 'Tribal-knowledge depopulation across four healthcare/insurance subs',
  context:
    "Four subsidiaries have key processes owned by single individuals with no documented succession. Beacon proposes a coordinated knowledge-depopulation initiative across these four subs, sharing methodology and named successor candidates.",
  status: 'PROPOSED',
  proposedAt: '2026-04-01T00:00:00Z',
  proposedBy: 'operator',
  activatedAt: null,
  resolvedAt: null,
  estimatedValue: '$1.2M annually across four subsidiaries',
  estimatedValueAmount: null,
  parties: [
    {
      id: 'party-beacon',
      partyLabel: 'Beacon Corporate Operations',
      partyType: 'ORG_UNIT',
      boundEntities: [],
      responseRequired: true,
      response: null,
      commitments: [
        {
          id: 'cmt-b1',
          text: 'Provide the knowledge-depopulation methodology and template',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-b2',
          text: 'Coordinate quarterly review across the four subsidiaries',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-b3',
          text: 'Surface successor-candidate gaps to subsidiary COOs within 30 days',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
      ],
    },
    {
      id: 'party-curasync',
      partyLabel: 'Curasync Clinical Systems',
      partyType: 'DOMAIN',
      boundEntities: [],
      responseRequired: true,
      response: null,
      commitments: [
        {
          id: 'cmt-c1',
          text: 'Identify and document the three highest-risk single-owner processes by Q3',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-c2',
          text: 'Name and onboard at least one successor candidate per process by Q4',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-c3',
          text: 'Report monthly progress against the Beacon-provided template',
          dueBy: null,
          cadence: 'MONTHLY',
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
      ],
    },
    {
      id: 'party-combined',
      partyLabel: 'Sentari, Tellix, Bursar (combined obligation)',
      partyType: 'COMBINED',
      boundEntities: [],
      responseRequired: true,
      response: null,
      commitments: [
        {
          id: 'cmt-s1',
          text: 'Each subsidiary mirrors the Curasync commitments on their own timeline',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-s2',
          text: 'Each provides quarterly methodology feedback to Beacon Corporate',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
      ],
    },
  ],
  enforcement: {
    monitor:
      "Beacon Corporate runs monthly compliance checks against each subsidiary's reported progress. Quarterly reviews validate successor-candidate adequacy.",
    monitorMechanism:
      'MCP coordination contract id co-tribal-knowledge-depopulation; substrate auto-flags subsidiary missing-monthly-report after 7 grace days; surface to Operating Partner dashboard.',
    declineTriggers: [
      {
        id: 'dt-1',
        text: 'Subsidiary cannot identify successor candidates due to specialized regulated role (e.g., licensed compliance officer in a state where bench is small)',
        armed: false,
        invocableByPartyId: null,
      },
      {
        id: 'dt-2',
        text: 'Subsidiary acquires a third party with overlapping process and proposes alternative consolidation',
        armed: false,
        invocableByPartyId: null,
      },
      {
        id: 'dt-3',
        text: "Beacon Corporate methodology proves insufficient for subsidiary's domain",
        armed: false,
        invocableByPartyId: null,
      },
    ],
  },
  linkedEntities: [
    subsidiaryLink('curasync-clinical', 'Curasync Clinical Systems'),
    subsidiaryLink('sentari-lab', 'Sentari'),
    subsidiaryLink('tellix-sensors', 'Tellix'),
    subsidiaryLink('bursar-campus', 'Bursar'),
  ],
  monitors: [
    {
      id: 'mon-1',
      name: 'missing-monthly-report',
      ruleType: 'ATTESTATION_CADENCE',
      status: 'SUSPENDED',
      cadence: 'MONTHLY',
      graceDays: 7,
      lastRunAt: null,
      nextRunAt: null,
      lastOutcome: null,
    },
  ],
  breachSummary: { open: 0, bySeverity: {} },
  reviewBoundary: null,
};

/** Expected view object — literal copy from the fixture FILE (see header). */
const EXPECTED_1: BeaconContract = {
  id: 'co-tribal-knowledge-depopulation',
  type: 'cross-portfolio-opportunity',
  title: 'Tribal-knowledge depopulation across four healthcare/insurance subs',
  context:
    "Four subsidiaries have key processes owned by single individuals with no documented succession. Beacon proposes a coordinated knowledge-depopulation initiative across these four subs, sharing methodology and named successor candidates.",
  parties: [
    {
      partyLabel: 'Beacon Corporate Operations',
      commitments: [
        'Provide the knowledge-depopulation methodology and template',
        'Coordinate quarterly review across the four subsidiaries',
        'Surface successor-candidate gaps to subsidiary COOs within 30 days',
      ],
    },
    {
      partyLabel: 'Curasync Clinical Systems',
      commitments: [
        'Identify and document the three highest-risk single-owner processes by Q3',
        'Name and onboard at least one successor candidate per process by Q4',
        'Report monthly progress against the Beacon-provided template',
      ],
    },
    {
      partyLabel: 'Sentari, Tellix, Bursar (combined obligation)',
      commitments: [
        'Each subsidiary mirrors the Curasync commitments on their own timeline',
        'Each provides quarterly methodology feedback to Beacon Corporate',
      ],
    },
  ],
  enforcement: {
    monitor:
      "Beacon Corporate runs monthly compliance checks against each subsidiary's reported progress. Quarterly reviews validate successor-candidate adequacy.",
    monitorMechanism:
      'MCP coordination contract id co-tribal-knowledge-depopulation; substrate auto-flags subsidiary missing-monthly-report after 7 grace days; surface to Operating Partner dashboard.',
    declineTriggers: [
      'Subsidiary cannot identify successor candidates due to specialized regulated role (e.g., licensed compliance officer in a state where bench is small)',
      'Subsidiary acquires a third party with overlapping process and proposes alternative consolidation',
      "Beacon Corporate methodology proves insufficient for subsidiary's domain",
    ],
  },
  estimatedValue: '$1.2M annually across four subsidiaries',
  linkedSubsidiarySlugs: [
    'curasync-clinical',
    'sentari-lab',
    'tellix-sensors',
    'bursar-campus',
  ],
  proposedAt: '2026-04-01',
};

/* ------------------------------------------------------------------ */
/*  Vector 2 — fi-shared-pre-fill-agent (§14.1)                        */
/* ------------------------------------------------------------------ */

const VECTOR_2: ContractDetailResponse = {
  id: '00000000-0000-0000-0000-000000000c02',
  contractKey: 'fi-shared-pre-fill-agent',
  type: 'federation-invitation',
  title: 'Shared compliance-form pre-fill agent (Polestar invites Curasync)',
  context:
    "Polestar has deployed a compliance-form pre-fill agent against HIPAA-attestation forms. Curasync's regulated workflows have similar form-attestation overhead. Polestar invites Curasync to join the federation; the agent expands its scope to include Curasync's form set in exchange for Curasync absorbing 40% of agent maintenance.",
  status: 'PROPOSED',
  proposedAt: '2026-04-15T00:00:00Z',
  proposedBy: 'operator',
  activatedAt: null,
  resolvedAt: null,
  // NO estimatedValue — §14.1 vector 2 pins null serialization preserved;
  // the adapter must keep the key ABSENT so the UI hides the value aside.
  estimatedValue: null,
  estimatedValueAmount: null,
  parties: [
    {
      id: 'party-polestar',
      partyLabel: 'Polestar Life Admin (federation host)',
      partyType: 'DOMAIN',
      boundEntities: [],
      responseRequired: true,
      response: null,
      commitments: [
        {
          id: 'cmt-p1',
          text: 'Maintain agent codebase and accept Curasync-submitted form additions',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-p2',
          text: "Federate agent oversight with Curasync's HIM Director per shared-oversight pattern",
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-p3',
          text: 'Provide quarterly utilization reporting to both parties',
          dueBy: null,
          cadence: 'QUARTERLY',
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
      ],
    },
    {
      id: 'party-curasync-2',
      partyLabel: 'Curasync Clinical Systems',
      partyType: 'DOMAIN',
      boundEntities: [],
      responseRequired: true,
      response: null,
      commitments: [
        {
          id: 'cmt-q1',
          text: 'Absorb 40% of agent maintenance cost',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-q2',
          text: "Submit Curasync's form-attestation set within 30 days of acceptance",
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
        {
          id: 'cmt-q3',
          text: 'Participate in joint quarterly review with Polestar',
          dueBy: null,
          cadence: null,
          complianceState: 'UNEVALUATED',
          lastEvaluatedAt: null,
        },
      ],
    },
  ],
  enforcement: {
    monitor:
      'Quarterly federated review evaluates utilization per subsidiary and cost-share fairness. Either party may invoke Decline at quarterly review boundaries.',
    monitorMechanism:
      'MCP federation contract id fi-shared-pre-fill-agent; substrate auto-flags utilization imbalance > 30% between parties for review.',
    // Trigger order is the FILE order (HIPAA, accuracy, cost-share) — the
    // §14.1 prose lists accuracy first, but the file is the source of truth.
    declineTriggers: [
      {
        id: 'dt-4',
        text: 'Curasync HIPAA attestation framework changes incompatibly with shared agent',
        armed: false,
        invocableByPartyId: null,
      },
      {
        // The arming demo: this trigger carries the {"metric":"accuracy","lt":0.90}
        // predicate on the backend (§14.1) — standing is a superset field the
        // adapter drops.
        id: 'dt-5',
        text: 'Polestar agent reaches accuracy threshold below 90%',
        armed: false,
        invocableByPartyId: null,
      },
      {
        id: 'dt-6',
        text: 'Cost-share imbalance > 30% sustained over two quarters',
        armed: false,
        invocableByPartyId: null,
      },
    ],
  },
  linkedEntities: [
    subsidiaryLink('curasync-clinical', 'Curasync Clinical Systems'),
    subsidiaryLink('polestar-life', 'Polestar Life Admin'),
  ],
  monitors: [
    {
      id: 'mon-2',
      name: 'utilization-imbalance',
      ruleType: 'METRIC_THRESHOLD',
      status: 'SUSPENDED',
      cadence: 'QUARTERLY',
      graceDays: 0,
      lastRunAt: null,
      nextRunAt: null,
      lastOutcome: null,
    },
  ],
  breachSummary: { open: 0, bySeverity: {} },
  reviewBoundary: null,
};

/** Expected view object — literal copy from the fixture FILE (see header). */
const EXPECTED_2: BeaconContract = {
  id: 'fi-shared-pre-fill-agent',
  type: 'federation-invitation',
  title: 'Shared compliance-form pre-fill agent (Polestar invites Curasync)',
  context:
    "Polestar has deployed a compliance-form pre-fill agent against HIPAA-attestation forms. Curasync's regulated workflows have similar form-attestation overhead. Polestar invites Curasync to join the federation; the agent expands its scope to include Curasync's form set in exchange for Curasync absorbing 40% of agent maintenance.",
  parties: [
    {
      partyLabel: 'Polestar Life Admin (federation host)',
      commitments: [
        'Maintain agent codebase and accept Curasync-submitted form additions',
        "Federate agent oversight with Curasync's HIM Director per shared-oversight pattern",
        'Provide quarterly utilization reporting to both parties',
      ],
    },
    {
      partyLabel: 'Curasync Clinical Systems',
      commitments: [
        'Absorb 40% of agent maintenance cost',
        "Submit Curasync's form-attestation set within 30 days of acceptance",
        'Participate in joint quarterly review with Polestar',
      ],
    },
  ],
  enforcement: {
    monitor:
      'Quarterly federated review evaluates utilization per subsidiary and cost-share fairness. Either party may invoke Decline at quarterly review boundaries.',
    monitorMechanism:
      'MCP federation contract id fi-shared-pre-fill-agent; substrate auto-flags utilization imbalance > 30% between parties for review.',
    declineTriggers: [
      'Curasync HIPAA attestation framework changes incompatibly with shared agent',
      'Polestar agent reaches accuracy threshold below 90%',
      'Cost-share imbalance > 30% sustained over two quarters',
    ],
  },
  linkedSubsidiarySlugs: ['curasync-clinical', 'polestar-life'],
  proposedAt: '2026-04-15',
};

/* ------------------------------------------------------------------ */
/*  §14.3 deep-equality pins                                           */
/* ------------------------------------------------------------------ */

describe('adaptContractDetail (engine-spec §7.3 / §14.3)', () => {
  it('vector 1 (co-tribal-knowledge-depopulation) deep-equals the fixture object', () => {
    const adapted = adaptContractDetail(VECTOR_1);
    expect(adapted).toStrictEqual(EXPECTED_1);
    expect(adapted).toStrictEqual(getContract('co-tribal-knowledge-depopulation'));
  });

  it('vector 2 (fi-shared-pre-fill-agent) deep-equals the fixture object', () => {
    const adapted = adaptContractDetail(VECTOR_2);
    expect(adapted).toStrictEqual(EXPECTED_2);
    expect(adapted).toStrictEqual(getContract('fi-shared-pre-fill-agent'));
  });

  it('maps id from contractKey, not the row UUID (§7.3 — click-throughs resolve by key)', () => {
    expect(adaptContractDetail(VECTOR_1).id).toBe('co-tribal-knowledge-depopulation');
    expect(adaptContractDetail(VECTOR_1).id).not.toBe(VECTOR_1.id);
  });

  it('keeps estimatedValue ABSENT (not undefined-valued, not null) when the wire value is null', () => {
    const adapted = adaptContractDetail(VECTOR_2);
    // §14.1 vector 2: null serialization preserved — UI hides the value
    // aside via `contract.estimatedValue &&`, and fixture deep-equality
    // requires the key to be missing entirely.
    expect('estimatedValue' in adapted).toBe(false);
  });

  it('takes only SUBSIDIARY_SLUG linked entities into linkedSubsidiarySlugs (§7.3)', () => {
    const withDomainLink: ContractDetailResponse = {
      ...VECTOR_2,
      linkedEntities: [
        {
          entityType: 'DOMAIN',
          entityId: 'dom-1',
          slug: null,
          label: 'Curasync Clinical Systems',
        },
        ...VECTOR_2.linkedEntities,
      ],
    };
    expect(adaptContractDetail(withDomainLink).linkedSubsidiarySlugs).toStrictEqual([
      'curasync-clinical',
      'polestar-life',
    ]);
  });

  it('trims proposedAt to the date part (§7.3)', () => {
    expect(adaptContractDetail(VECTOR_1).proposedAt).toBe('2026-04-01');
    expect(adaptContractDetail(VECTOR_2).proposedAt).toBe('2026-04-15');
  });
});
