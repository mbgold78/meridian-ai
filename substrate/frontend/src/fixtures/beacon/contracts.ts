/**
 * Coordination contract fixtures for the Beacon Industries demo.
 *
 * A coordination contract is the substrate's MCP-enforced governance
 * primitive (P19). Federation invitations and cross-portfolio
 * opportunities aren't proposals — they're enforceable contracts with
 * explicit parties, commitments, monitoring, and decline triggers.
 *
 * Stage 6 governance-3 introduces two contracts so the detail surface
 * has both a `cross-portfolio-opportunity` example and a
 * `federation-invitation` example. Each links back to one or more
 * subsidiaries via `linkedSubsidiarySlugs` (real fixture slugs only,
 * per checkpoint queue item #14).
 */

import type { BeaconContract } from './types';

export const BEACON_CONTRACTS: BeaconContract[] = [
  {
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
  },
  {
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
  },
];

const contractsById: Map<string, BeaconContract> = new Map(
  BEACON_CONTRACTS.map((c) => [c.id, c]),
);

/**
 * Look up a contract by id. Returns undefined when the id isn't known —
 * callers should treat missing as a 404 in the demo router.
 */
export function getContract(id: string): BeaconContract | undefined {
  return contractsById.get(id);
}
