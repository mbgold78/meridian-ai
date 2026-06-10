import type {
  BeaconCoordinationSignal,
  BeaconCrossOpportunity,
  BeaconSubsidiary,
} from './types';

/**
 * Cross-portfolio AI-surfaced opportunities. These reference subsidiaries
 * by slug; the index module ensures the slugs resolve to real entries.
 */
export const CROSS_PORTFOLIO_OPPORTUNITIES: BeaconCrossOpportunity[] = [
  {
    id: 'opp-payment-recon',
    title: 'Shared payment-reconciliation agent across financial books',
    detail:
      'Brokerline (commission match), RevSync (claims payment posting), Polestar (premium billing), and ConstructLedger (government invoice payments) all run lightly-customized variants of the same reconciliation workflow. A federated agent could absorb ~80% of the manual matching effort.',
    affectedSubsidiarySlugs: [
      'brokerline',
      'revsync-health',
      'polestar-life',
      'constructledger',
    ],
    estimatedAnnualValueDollars: 2_400_000,
  },
  {
    id: 'opp-email-extract',
    title: 'Cross-portfolio email-extraction primitive',
    detail:
      'Three subsidiaries process structured intent from inbound email today: Brokerline (endorsements), Casebridge (matter intake), ClaimRiver (FNOL forwards). Same problem, three different homegrown approaches.',
    affectedSubsidiarySlugs: ['brokerline', 'casebridge', 'claimriver'],
    estimatedAnnualValueDollars: 1_800_000,
  },
  {
    id: 'opp-reg-reporting',
    title: 'Federated regulatory-reporting playbook',
    detail:
      'Cardient (FDA MDR), Polestar (NAIC), ScholarPath (IPEDS), AtlasGIS (state grant reporting) all maintain hand-built filing pipelines. A shared reporting primitive — schema-out, schedule-driven, audit-logged — would eliminate the duplicated control infrastructure.',
    affectedSubsidiarySlugs: [
      'cardient-medical',
      'polestar-life',
      'scholarpath-sis',
      'atlas-gis',
    ],
    estimatedAnnualValueDollars: 900_000,
  },
  {
    id: 'opp-tribal',
    title: 'Tribal-knowledge depopulation initiative',
    detail:
      'Sentari (specimen routing), Tellix (custom spec translation), Bursar (refund processing), and Curasync (insurance verification) each have a single-person bottleneck flagged by Meridian. Coordinated process-capture program could de-risk all four.',
    affectedSubsidiarySlugs: [
      'sentari-lab',
      'tellix-sensors',
      'bursar-campus',
      'curasync-clinical',
    ],
    estimatedAnnualValueDollars: 1_200_000,
    contractId: 'co-tribal-knowledge-depopulation',
  },
  {
    id: 'opp-sla-primitive',
    title: 'Common SLA tracking primitive',
    detail:
      'Civicore (citizen requests), Casebridge (matter SLAs), Lanechain (carrier exceptions), and Sentari (critical values) all reinvent the same tracking model. Rolling this up would also unlock cross-portfolio SLA benchmarking.',
    affectedSubsidiarySlugs: [
      'civicore',
      'casebridge',
      'lanechain',
      'sentari-lab',
    ],
    estimatedAnnualValueDollars: 700_000,
  },
];

/**
 * Builds the dashboard's coordination signal strip directly from the
 * subsidiary catalog so the counts can never drift from the underlying
 * fixtures. The one exception is `past-target`, which the demo
 * narrative carries as a soft stat — it isn't tracked in subsidiary
 * fixtures, so it's stamped from the narrative spec.
 */
export function buildCoordinationSignals(
  subs: readonly BeaconSubsidiary[],
  crossOpportunityCount: number,
): BeaconCoordinationSignal[] {
  const plugInCount = subs.filter((s) => s.lifecyclePhase === 'plug-in').length;
  const tribalSubCount = subs.filter((s) =>
    s.processes.some((p) => p.tribalKnowledge === 'high'),
  ).length;
  const interventionCount = subs.reduce(
    (sum, s) => sum + s.agents.filter((a) => a.status === 'intervention-required').length,
    0,
  );

  return [
    { id: 'sig-live', label: 'Subsidiaries live on Meridian', count: subs.length, tone: 'success' },
    { id: 'sig-plug-in', label: 'Subsidiaries in Plug-In phase', count: plugInCount, tone: 'info' },
    { id: 'sig-tribal', label: 'Subsidiaries with high tribal-knowledge concentration', count: tribalSubCount, tone: 'warn' },
    { id: 'sig-intervention', label: 'Agents currently in intervention-required state', count: interventionCount, tone: 'alert' },
    { id: 'sig-cross', label: 'Active cross-portfolio opportunities', count: crossOpportunityCount, tone: 'info' },
    { id: 'sig-past-target', label: 'Subsidiaries past target lifecycle date', count: 3, tone: 'warn' },
  ];
}

export const BEACON_COMPANY_NAME = 'Beacon Industries';
export const BEACON_HQ_CITY = 'Atlanta, GA';
