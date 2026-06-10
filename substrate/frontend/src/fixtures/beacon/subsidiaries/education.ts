import type { BeaconSubsidiary } from '../types';

const bursar: BeaconSubsidiary = {
  slug: 'bursar-campus',
  name: 'Bursar Campus Operations',
  description:
    'Auxiliary services platform for higher education: campus IDs, dining, housing, account funding. Deployed at 410 colleges and universities.',
  industry: 'education',
  industryDetail: 'Campus auxiliary services',
  revenueMillions: 125,
  headcount: 280,
  hqCity: 'Ithaca, NY',
  cooName: 'Lawrence Stein',
  lifecyclePhase: 'plug-in',
  aiReadiness: 'low',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'id', parentId: null, level: 0, name: 'Campus ID' },
    { id: 'id-iss', parentId: 'id', level: 1, name: 'Issuance' },
    { id: 'id-rep', parentId: 'id', level: 1, name: 'Replacement' },
    { id: 'din', parentId: null, level: 0, name: 'Dining' },
    { id: 'din-meal', parentId: 'din', level: 1, name: 'Meal Plans' },
    { id: 'din-pos', parentId: 'din', level: 1, name: 'POS Operations' },
    { id: 'hous', parentId: null, level: 0, name: 'Housing' },
    { id: 'hous-assi', parentId: 'hous', level: 1, name: 'Room Assignment' },
    { id: 'acc', parentId: null, level: 0, name: 'Account Funding' },
  ],
  processes: [
    { id: 'p-photo-id', capabilityId: 'id-iss', name: 'Photo ID issuance', systemIds: ['bs-hub', 'campuskey'] },
    { id: 'p-lost-card', capabilityId: 'id-rep', name: 'Lost card replacement', systemIds: ['bs-hub'] },
    { id: 'p-meal-sign', capabilityId: 'din-meal', name: 'Meal plan signup', systemIds: ['bs-hub'] },
    { id: 'p-pos-sync', capabilityId: 'din-pos', name: 'Cafe POS sync', systemIds: ['cardwave-bridge'] },
    { id: 'p-room-lottery', capabilityId: 'hous-assi', name: 'Room assignment lottery', systemIds: ['bs-hub'] },
    { id: 'p-add-funds', capabilityId: 'acc', name: 'Add-funds online', systemIds: ['bs-hub'], tribalKnowledge: 'medium' },
  ],
  systems: [
    { id: 'bs-hub', name: 'Bursar Hub', kind: 'lob', vendor: 'Bursar' },
    { id: 'campuskey', name: 'CampusKey ID Hardware', kind: 'custom', vendor: 'CampusKey' },
    { id: 'cardwave-bridge', name: 'CardWave Bridge', kind: 'custom', vendor: 'Bursar' },
  ],
  findings: [
    {
      id: 'f-refund',
      severity: 'medium',
      title: 'Meal plan refunds processed manually by 4 different staff members',
      detail:
        'Each staff member uses their own spreadsheet; reconciliation drift up to $40k per term per campus.',
      processId: 'p-add-funds',
      surfacedAt: '2026-04-13',
    },
  ],
  agents: [
    { id: 'a-refund', name: 'Refund Eligibility Checker', role: 'Pre-validates refund requests against policy', status: 'shadow', recentActivity: 'Pilot at 4 campuses; 91% policy match', adherence: 91 },
  ],
  initiatives: [
    { id: 'i-meridian-standup', title: 'Stand up Meridian capability model', status: 'in-flight', owner: 'Lawrence Stein', targetDate: '2026-08-15', valueDollars: 0, valueCycleTimeReductionPct: 0, progressPct: 30 },
  ],
};

const scholarpath: BeaconSubsidiary = {
  slug: 'scholarpath-sis',
  name: 'ScholarPath SIS',
  description:
    'Student information system for higher education; manages records, financial aid, admissions for 540 institutions.',
  industry: 'education',
  industryDetail: 'Higher-ed student information',
  revenueMillions: 145,
  headcount: 325,
  hqCity: 'Raleigh, NC',
  cooName: 'Imani Carter',
  lifecyclePhase: 'design',
  aiReadiness: 'medium',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'rec', parentId: null, level: 0, name: 'Records' },
    { id: 'rec-reg', parentId: 'rec', level: 1, name: 'Registration' },
    { id: 'rec-tran', parentId: 'rec', level: 1, name: 'Transcripts' },
    { id: 'fin', parentId: null, level: 0, name: 'Financial Aid' },
    { id: 'fin-app', parentId: 'fin', level: 1, name: 'FAFSA Intake' },
    { id: 'fin-awd', parentId: 'fin', level: 1, name: 'Awarding' },
    { id: 'fin-dis', parentId: 'fin', level: 1, name: 'Disbursement' },
    { id: 'adm', parentId: null, level: 0, name: 'Admissions' },
    { id: 'adm-app', parentId: 'adm', level: 1, name: 'Application Intake' },
    { id: 'adm-dec', parentId: 'adm', level: 1, name: 'Decisioning' },
    { id: 'bil', parentId: null, level: 0, name: 'Student Billing' },
    { id: 'rep', parentId: null, level: 0, name: 'Federal Reporting' },
  ],
  processes: [
    { id: 'p-reg', capabilityId: 'rec-reg', name: 'Course registration', systemIds: ['sp-core'] },
    { id: 'p-tran', capabilityId: 'rec-tran', name: 'Transcript request fulfillment', systemIds: ['sp-core'], tribalKnowledge: 'medium' },
    { id: 'p-fafsa', capabilityId: 'fin-app', name: 'FAFSA verification', systemIds: ['sp-core'] },
    { id: 'p-awd', capabilityId: 'fin-awd', name: 'Awarding packaging', systemIds: ['sp-core'], tribalKnowledge: 'high' },
    { id: 'p-dis', capabilityId: 'fin-dis', name: 'Disbursement waterfall', systemIds: ['sp-core'] },
    { id: 'p-app-read', capabilityId: 'adm-app', name: 'Application read & route', systemIds: ['sp-core', 'common-app'] },
    { id: 'p-comm-dec', capabilityId: 'adm-dec', name: 'Committee decision capture', systemIds: ['sp-core'] },
    { id: 'p-tuition', capabilityId: 'bil', name: 'Tuition statement generation', systemIds: ['sp-core'] },
    { id: 'p-ipeds', capabilityId: 'rep', name: 'IPEDS submission', systemIds: ['sp-core', 'nslds'] },
  ],
  systems: [
    { id: 'sp-core', name: 'ScholarPath Core', kind: 'lob', vendor: 'ScholarPath' },
    { id: 'common-app', name: 'Common App Connector', kind: 'custom', vendor: 'ScholarPath' },
    { id: 'nslds', name: 'NSLDS Reporter', kind: 'custom', vendor: 'ScholarPath' },
  ],
  findings: [
    {
      id: 'f-awd-ovr',
      severity: 'high',
      title: 'Awarding logic has 5 institution-specific overrides per campus on average',
      detail:
        'Each campus customizes awarding rules in code; releases require per-customer regression. Ships 6 weeks behind plan.',
      processId: 'p-awd',
      surfacedAt: '2026-02-22',
    },
    {
      id: 'f-tran-sla',
      severity: 'medium',
      title: 'Transcript request SLA varies 3-14 days',
      detail:
        'No standard fulfillment workflow; some campuses still print + mail.',
      processId: 'p-tran',
      surfacedAt: '2026-04-16',
    },
  ],
  agents: [
    { id: 'a-fafsa', name: 'FAFSA Verification Assistant', role: 'Pre-validates FAFSA documentation', status: 'live', recentActivity: '18k applications reviewed this cycle', adherence: 93 },
    { id: 'a-app-read', name: 'Application Reader', role: 'Pre-reads applications for committee', status: 'shadow', recentActivity: 'Pilot at 9 institutions; 82% reader concordance', adherence: 82 },
    { id: 'a-awd-whatif', name: 'Awarding What-If', role: 'Models awarding scenarios for advisors', status: 'live', recentActivity: '4,200 scenarios run this term', adherence: 95 },
  ],
  initiatives: [
    { id: 'i-tran-24', title: 'Automate transcript request to <24h', status: 'in-flight', owner: 'Imani Carter', targetDate: '2026-10-15', valueDollars: 1_800_000, valueCycleTimeReductionPct: 85, progressPct: 50 },
    { id: 'i-awd-rules', title: 'Configurable awarding rules engine', status: 'in-flight', owner: 'Reggie Walls', targetDate: '2027-01-31', valueDollars: 8_900_000, valueCycleTimeReductionPct: 60, progressPct: 35 },
  ],
};

export const EDUCATION_SUBSIDIARIES: BeaconSubsidiary[] = [
  bursar,
  scholarpath,
];
