import type { BeaconSubsidiary } from '../types';

const casebridge: BeaconSubsidiary = {
  slug: 'casebridge',
  name: 'Casebridge',
  description:
    'Practice management for mid-market law firms (50-300 attorneys); 1,400 firm customers across the US.',
  industry: 'legal',
  industryDetail: 'Mid-market law firm PM',
  revenueMillions: 165,
  headcount: 360,
  hqCity: 'Chicago, IL',
  cooName: 'Tessa Munro',
  lifecyclePhase: 'design',
  aiReadiness: 'medium',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'mat', parentId: null, level: 0, name: 'Matter Management' },
    { id: 'mat-intk', parentId: 'mat', level: 1, name: 'Matter Intake' },
    { id: 'mat-bud', parentId: 'mat', level: 1, name: 'Budgeting' },
    { id: 'time', parentId: null, level: 0, name: 'Time & Billing' },
    { id: 'time-cap', parentId: 'time', level: 1, name: 'Time Capture' },
    { id: 'time-inv', parentId: 'time', level: 1, name: 'Invoice Generation' },
    { id: 'time-col', parentId: 'time', level: 1, name: 'Collections' },
    { id: 'doc', parentId: null, level: 0, name: 'Document Management' },
    { id: 'doc-tem', parentId: 'doc', level: 1, name: 'Templates' },
    { id: 'doc-rev', parentId: 'doc', level: 1, name: 'Review & Redlining' },
    { id: 'cli', parentId: null, level: 0, name: 'Client Portal' },
  ],
  processes: [
    { id: 'p-conflict', capabilityId: 'mat-intk', name: 'Conflict check', systemIds: ['cb-prac'] },
    { id: 'p-bud-setup', capabilityId: 'mat-bud', name: 'Matter budget setup', systemIds: ['cb-prac'] },
    { id: 'p-mob-time', capabilityId: 'time-cap', name: 'Mobile timer entry', systemIds: ['cb-prac'] },
    { id: 'p-utbms', capabilityId: 'time-inv', name: 'e-Billing UTBMS coding', systemIds: ['cb-prac'], tribalKnowledge: 'high' },
    { id: 'p-ar', capabilityId: 'time-col', name: 'AR aging follow-up', systemIds: ['cb-prac'] },
    { id: 'p-clauses', capabilityId: 'doc-tem', name: 'Template clause library', systemIds: ['matterstack'] },
    { id: 'p-redline', capabilityId: 'doc-rev', name: 'Redline tracking', systemIds: ['matterstack', 'm365'] },
    { id: 'p-cli-share', capabilityId: 'cli', name: 'Client document share', systemIds: ['cb-prac'] },
  ],
  systems: [
    { id: 'cb-prac', name: 'Casebridge Practice', kind: 'lob', vendor: 'Casebridge' },
    { id: 'matterstack', name: 'MatterStack Integration', kind: 'custom', vendor: 'MatterStack' },
    { id: 'm365', name: 'Microsoft 365', kind: 'collaboration', vendor: 'Microsoft' },
  ],
  findings: [
    {
      id: 'f-utbms-var',
      severity: 'high',
      title: 'UTBMS coding inconsistent across attorneys',
      detail:
        'Same task billed under 3 different codes by different partners; client e-billing rejection rate 11%.',
      processId: 'p-utbms',
      surfacedAt: '2026-03-21',
    },
    {
      id: 'f-ar-aged',
      severity: 'medium',
      title: 'AR > 90 days at 18% of book',
      detail:
        'Industry benchmark 11%; concentrated in 3 practice areas.',
      processId: 'p-ar',
      surfacedAt: '2026-04-04',
    },
  ],
  agents: [
    { id: 'a-time-sug', name: 'Time Entry Suggester', role: 'Suggests time entries from calendar + email', status: 'live', recentActivity: '24,800 entries suggested this week', adherence: 94 },
    { id: 'a-utbms', name: 'UTBMS Coder', role: 'Auto-codes time narratives to UTBMS', status: 'shadow', recentActivity: 'Pilot at 14 firms; 86% partner acceptance', adherence: 86 },
    { id: 'a-conflict', name: 'Conflict-Check Researcher', role: 'Runs conflict scans on new intake', status: 'live', recentActivity: '420 conflicts surfaced this month', adherence: 96 },
  ],
  initiatives: [
    { id: 'i-daily-close', title: 'Daily-close time entry program', status: 'in-flight', owner: 'Tessa Munro', targetDate: '2026-09-30', valueDollars: 5_400_000, valueCycleTimeReductionPct: 50, progressPct: 60 },
    { id: 'i-ar-60', title: 'AR < 60 days', status: 'proposed', owner: 'Marcus Yi', targetDate: '2026-12-31', valueDollars: 3_100_000, valueCycleTimeReductionPct: 33, progressPct: 0 },
  ],
};

const docvault: BeaconSubsidiary = {
  slug: 'docvault-legal',
  name: 'DocVault Legal',
  description:
    'Document management and assembly platform for legal teams; deployed at 740 firms and corporate legal departments.',
  industry: 'legal',
  industryDetail: 'Legal document management',
  revenueMillions: 110,
  headcount: 245,
  hqCity: 'Minneapolis, MN',
  cooName: 'Aiden Foley',
  lifecyclePhase: 'plug-in',
  aiReadiness: 'low',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'sto', parentId: null, level: 0, name: 'Document Storage' },
    { id: 'sto-vau', parentId: 'sto', level: 1, name: 'Vault & Versioning' },
    { id: 'sto-sec', parentId: 'sto', level: 1, name: 'Access Control' },
    { id: 'asm', parentId: null, level: 0, name: 'Document Assembly' },
    { id: 'asm-tem', parentId: 'asm', level: 1, name: 'Template Library' },
    { id: 'asm-clause', parentId: 'asm', level: 1, name: 'Clause Library' },
    { id: 'sig', parentId: null, level: 0, name: 'E-Signature' },
  ],
  processes: [
    { id: 'p-mat-store', capabilityId: 'sto-vau', name: 'Matter-scoped storage', systemIds: ['dv-cloud'] },
    { id: 'p-eth-wall', capabilityId: 'sto-sec', name: 'Ethical wall enforcement', systemIds: ['dv-cloud', 'okta'] },
    { id: 'p-eng-letter', capabilityId: 'asm-tem', name: 'Engagement letter assembly', systemIds: ['dv-cloud'] },
    { id: 'p-clause-swap', capabilityId: 'asm-clause', name: 'Clause swap', systemIds: ['dv-cloud'], tribalKnowledge: 'medium' },
    { id: 'p-multi-sig', capabilityId: 'sig', name: 'Multi-party signature flow', systemIds: ['docusign'] },
  ],
  systems: [
    { id: 'dv-cloud', name: 'DocVault Cloud', kind: 'lob', vendor: 'DocVault' },
    { id: 'docusign', name: 'DocuSign API', kind: 'custom', vendor: 'DocuSign' },
    { id: 'okta', name: 'Okta SSO', kind: 'identity', vendor: 'Okta' },
  ],
  findings: [
    {
      id: 'f-clause-dup',
      severity: 'medium',
      title: 'Clause library has duplicate clauses with subtle variants',
      detail:
        'Audit found 240 near-duplicate clauses across customer libraries; risk of attorneys grabbing the wrong variant.',
      processId: 'p-clause-swap',
      surfacedAt: '2026-04-11',
    },
  ],
  agents: [
    { id: 'a-clause-sim', name: 'Clause Similarity Detector', role: 'Detects near-duplicate clauses', status: 'shadow', recentActivity: 'Pilot at 6 customers; surfaced 880 duplicates', adherence: 89 },
  ],
  initiatives: [
    { id: 'i-meridian-standup', title: 'Stand up Meridian capability model', status: 'in-flight', owner: 'Aiden Foley', targetDate: '2026-07-31', valueDollars: 0, valueCycleTimeReductionPct: 0, progressPct: 30 },
  ],
};

export const LEGAL_SUBSIDIARIES: BeaconSubsidiary[] = [
  casebridge,
  docvault,
];
