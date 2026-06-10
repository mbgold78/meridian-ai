import type { BeaconSubsidiary } from '../types';

const stratafold: BeaconSubsidiary = {
  slug: 'stratafold',
  name: 'Stratafold Planning',
  description:
    'Corporate planning and EPM platform for mid-market and Fortune 1000 companies; serves 920 customers across the Americas and EMEA.',
  industry: 'financial',
  industryDetail: 'Corporate planning / EPM',
  revenueMillions: 190,
  headcount: 420,
  hqCity: 'Boston, MA',
  cooName: 'Naomi Greenberg',
  lifecyclePhase: 'design',
  aiReadiness: 'medium',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'mod', parentId: null, level: 0, name: 'Modeling Platform' },
    { id: 'mod-cube', parentId: 'mod', level: 1, name: 'Cube Management' },
    { id: 'mod-calc', parentId: 'mod', level: 1, name: 'Calculation Engine' },
    { id: 'work', parentId: null, level: 0, name: 'Workflow & Collaboration' },
    { id: 'work-cycle', parentId: 'work', level: 1, name: 'Planning Cycles' },
    { id: 'work-rev', parentId: 'work', level: 1, name: 'Review & Approval' },
    { id: 'data', parentId: null, level: 0, name: 'Data Integration' },
    { id: 'data-src', parentId: 'data', level: 1, name: 'Source Connectors' },
    { id: 'data-val', parentId: 'data', level: 1, name: 'Data Validation' },
    { id: 'rep', parentId: null, level: 0, name: 'Reporting & Boards' },
  ],
  processes: [
    { id: 'p-cube-refresh', capabilityId: 'mod-cube', name: 'Nightly cube refresh', systemIds: ['sf-cloud'] },
    { id: 'p-driver-fcst', capabilityId: 'mod-calc', name: 'Driver-based forecasting', systemIds: ['sf-cloud'], tribalKnowledge: 'medium' },
    { id: 'p-q-cycle', capabilityId: 'work-cycle', name: 'Quarter-by-quarter planning cycle', systemIds: ['sf-cloud'] },
    { id: 'p-approve', capabilityId: 'work-rev', name: 'Multi-tier approval routing', systemIds: ['sf-cloud'] },
    { id: 'p-src-map', capabilityId: 'data-src', name: 'Source mapping wizard', systemIds: ['sf-cloud', 'wd-conn'] },
    { id: 'p-data-check', capabilityId: 'data-val', name: 'Data anomaly check', systemIds: ['sf-cloud'] },
    { id: 'p-board-pub', capabilityId: 'rep', name: 'Board pack publishing', systemIds: ['sf-cloud'] },
  ],
  systems: [
    { id: 'sf-cloud', name: 'Stratafold Cloud', kind: 'lob', vendor: 'Stratafold' },
    { id: 'snow', name: 'Snowflake', kind: 'analytics', vendor: 'Snowflake' },
    { id: 'wd-conn', name: 'Workday Connector', kind: 'custom', vendor: 'Stratafold' },
  ],
  findings: [
    {
      id: 'f-driver-setup',
      severity: 'high',
      title: 'Driver-based forecasting setup time is 4× industry benchmark',
      detail:
        'Average new-customer setup 92 days vs. 23 days for nearest competitor; renewal risk concentrated in slow setups.',
      processId: 'p-driver-fcst',
      surfacedAt: '2026-03-14',
    },
  ],
  agents: [
    { id: 'a-var-comm', name: 'Variance Commentary Drafter', role: 'Drafts MD&A-style commentary on variances', status: 'live', recentActivity: '320 narratives drafted this cycle', adherence: 92 },
    { id: 'a-src-map', name: 'Source Mapping Suggester', role: 'Suggests field mappings from source schemas', status: 'shadow', recentActivity: 'Pilot at 12 customers; 78% suggestions accepted', adherence: 78 },
  ],
  initiatives: [
    { id: 'i-setup-30', title: 'Cut new-customer setup from 90d to 30d', status: 'in-flight', owner: 'Naomi Greenberg', targetDate: '2026-10-31', valueDollars: 7_400_000, valueCycleTimeReductionPct: 67, progressPct: 55 },
  ],
};

const treasura: BeaconSubsidiary = {
  slug: 'treasura',
  name: 'Treasura',
  description:
    'Treasury management system for corporate treasurers; serves 380 mid-market and large-cap corporates.',
  industry: 'financial',
  industryDetail: 'Treasury management',
  revenueMillions: 145,
  headcount: 310,
  hqCity: 'New York, NY',
  cooName: 'Akhil Banerjee',
  lifecyclePhase: 'plug-in',
  aiReadiness: 'low',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'cash', parentId: null, level: 0, name: 'Cash Management' },
    { id: 'cash-pos', parentId: 'cash', level: 1, name: 'Cash Position' },
    { id: 'cash-fcst', parentId: 'cash', level: 1, name: 'Cash Forecast' },
    { id: 'pay', parentId: null, level: 0, name: 'Payments' },
    { id: 'pay-dom', parentId: 'pay', level: 1, name: 'Domestic ACH/Wire' },
    { id: 'pay-intl', parentId: 'pay', level: 1, name: 'Cross-Border' },
    { id: 'inv', parentId: null, level: 0, name: 'Investment & Debt' },
    { id: 'bnk', parentId: null, level: 0, name: 'Bank Connectivity' },
  ],
  processes: [
    { id: 'p-pos-rollup', capabilityId: 'cash-pos', name: 'Daily cash position roll-up', systemIds: ['tr-plat'] },
    { id: 'p-13w-fcst', capabilityId: 'cash-fcst', name: '13-week rolling forecast', systemIds: ['tr-plat'] },
    { id: 'p-dom-rel', capabilityId: 'pay-dom', name: 'Domestic payment release', systemIds: ['tr-plat'] },
    { id: 'p-fx-hedge', capabilityId: 'pay-intl', name: 'FX hedging tickets', systemIds: ['tr-plat', 'swift'] },
    { id: 'p-mm-sweep', capabilityId: 'inv', name: 'Money market sweep', systemIds: ['tr-plat'] },
    { id: 'p-stmt-parse', capabilityId: 'bnk', name: 'Bank statement parsing', systemIds: ['tr-plat', 'plaid'], tribalKnowledge: 'medium' },
  ],
  systems: [
    { id: 'tr-plat', name: 'Treasura Platform', kind: 'lob', vendor: 'Treasura' },
    { id: 'swift', name: 'SWIFT Gateway', kind: 'custom', vendor: 'SWIFT' },
    { id: 'plaid', name: 'Plaid Bank Links', kind: 'custom', vendor: 'Plaid' },
  ],
  findings: [
    {
      id: 'f-stmt-parse',
      severity: 'medium',
      title: 'Bank statement parsing breaks for 4 EU bank formats',
      detail:
        'EU expansion stalled because statements from Deutsche, ING, Santander, BNP variants need manual reformat.',
      processId: 'p-stmt-parse',
      surfacedAt: '2026-04-06',
    },
  ],
  agents: [
    { id: 'a-pay-sanity', name: 'Payment Sanity Checker', role: 'Pre-validates payment files for fraud signatures', status: 'live', recentActivity: '94k payments screened this month', adherence: 99 },
  ],
  initiatives: [
    { id: 'i-meridian-standup', title: 'Stand up Meridian capability model', status: 'in-flight', owner: 'Akhil Banerjee', targetDate: '2026-07-15', valueDollars: 0, valueCycleTimeReductionPct: 0, progressPct: 25 },
  ],
};

export const FINANCIAL_SUBSIDIARIES: BeaconSubsidiary[] = [
  stratafold,
  treasura,
];
