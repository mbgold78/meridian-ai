import type { BeaconSubsidiary } from '../types';

const lanechain: BeaconSubsidiary = {
  slug: 'lanechain',
  name: 'Lanechain Freight Match',
  description:
    'Digital freight matching platform connecting shippers and carriers; 1.6M loads matched annually.',
  industry: 'logistics',
  industryDetail: 'Digital freight matching',
  revenueMillions: 210,
  headcount: 480,
  hqCity: 'Dallas, TX',
  cooName: 'Quentin Vasquez',
  lifecyclePhase: 'plan',
  aiReadiness: 'high',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'shp', parentId: null, level: 0, name: 'Shipper Operations' },
    { id: 'shp-quo', parentId: 'shp', level: 1, name: 'Quoting' },
    { id: 'shp-ten', parentId: 'shp', level: 1, name: 'Tendering' },
    { id: 'car', parentId: null, level: 0, name: 'Carrier Operations' },
    { id: 'car-on', parentId: 'car', level: 1, name: 'Onboarding' },
    { id: 'car-mat', parentId: 'car', level: 1, name: 'Match Engine' },
    { id: 'exec', parentId: null, level: 0, name: 'Execution' },
    { id: 'exec-tra', parentId: 'exec', level: 1, name: 'In-Transit Tracking' },
    { id: 'exec-exc', parentId: 'exec', level: 1, name: 'Exception Handling' },
    { id: 'set', parentId: null, level: 0, name: 'Settlement' },
    { id: 'set-inv', parentId: 'set', level: 1, name: 'Invoicing' },
    { id: 'set-pay', parentId: 'set', level: 1, name: 'Carrier Payment' },
    { id: 'rategrid', parentId: null, level: 0, name: 'Data & Pricing' },
  ],
  processes: [
    { id: 'p-spot-quote', capabilityId: 'shp-quo', name: 'Spot-rate quoting', systemIds: ['lc-plat', 'rategrid'] },
    { id: 'p-auto-tender', capabilityId: 'shp-ten', name: 'Auto-tender to preferred carrier', systemIds: ['lc-plat', 'mcleod'] },
    { id: 'p-kyc', capabilityId: 'car-on', name: 'Carrier KYC', systemIds: ['lc-plat'] },
    { id: 'p-match-score', capabilityId: 'car-mat', name: 'Real-time match scoring', systemIds: ['lc-plat'] },
    { id: 'p-eld-track', capabilityId: 'exec-tra', name: 'ELD-based tracking', systemIds: ['lc-plat'] },
    { id: 'p-dwell', capabilityId: 'exec-exc', name: 'Dwell-time exception handling', systemIds: ['lc-plat'], tribalKnowledge: 'high' },
    { id: 'p-pod-inv', capabilityId: 'set-inv', name: 'Auto-invoice on POD', systemIds: ['lc-plat'] },
    { id: 'p-quick-pay', capabilityId: 'set-pay', name: 'Quick-pay carrier program', systemIds: ['lc-plat'] },
    { id: 'p-lane-price', capabilityId: 'rategrid', name: 'Lane pricing model refresh', systemIds: ['lc-plat', 'rategrid'] },
  ],
  systems: [
    { id: 'lc-plat', name: 'Lanechain Platform', kind: 'lob', vendor: 'Lanechain' },
    { id: 'mcleod', name: 'McLeod TMS Connector', kind: 'custom', vendor: 'Lanechain' },
    { id: 'rategrid', name: 'RateGrid Rate Feed', kind: 'analytics', vendor: 'RateGrid' },
  ],
  findings: [
    {
      id: 'f-exc-paths',
      severity: 'high',
      title: 'Exception handling has 6 escalation paths depending on shipper',
      detail:
        'Each top-50 shipper has a custom escalation tree; ops team can\'t keep them straight, drives 22% rework on exception tickets.',
      processId: 'p-dwell',
      surfacedAt: '2026-03-26',
    },
  ],
  agents: [
    { id: 'a-quote-pricer', name: 'Quote Pricer', role: 'Generates dynamic spot quotes', status: 'live', recentActivity: '38k quotes priced this week', adherence: 95 },
    { id: 'a-match-score', name: 'Match Scorer', role: 'Scores carrier-load fit in real time', status: 'live', recentActivity: '180k matches scored', adherence: 93 },
    { id: 'a-dwell-tri', name: 'Dwell-Time Triager', role: 'Triages dwell exceptions to ops', status: 'intervention-required', recentActivity: 'Routing rules drift after onboarding new shipper', adherence: 69 },
    { id: 'a-pod-val', name: 'POD Validator', role: 'Validates POD completeness for auto-invoice', status: 'live', recentActivity: '24k PODs validated', adherence: 97 },
  ],
  initiatives: [
    { id: 'i-single-exc', title: 'Single exception escalation playbook', status: 'in-flight', owner: 'Quentin Vasquez', targetDate: '2026-08-31', valueDollars: 3_700_000, valueCycleTimeReductionPct: 45, progressPct: 60 },
    { id: 'i-pred-dwell', title: 'Predictive dwell forecasting', status: 'proposed', owner: 'Karen Yates', targetDate: '2026-12-15', valueDollars: 5_200_000, valueCycleTimeReductionPct: 30, progressPct: 5 },
  ],
};

const wheelops: BeaconSubsidiary = {
  slug: 'wheelops',
  name: 'WheelOps Fleet',
  description:
    'Fleet management telematics SaaS for trucking and last-mile fleets; 380,000 vehicles connected.',
  industry: 'logistics',
  industryDetail: 'Fleet telematics',
  revenueMillions: 175,
  headcount: 395,
  hqCity: 'Salt Lake City, UT',
  cooName: 'Audrey Ng',
  lifecyclePhase: 'map',
  aiReadiness: 'medium',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'tel', parentId: null, level: 0, name: 'Telematics Ingest' },
    { id: 'tel-dev', parentId: 'tel', level: 1, name: 'Device Provisioning' },
    { id: 'tel-str', parentId: 'tel', level: 1, name: 'Stream Processing' },
    { id: 'ops', parentId: null, level: 0, name: 'Fleet Operations' },
    { id: 'ops-rou', parentId: 'ops', level: 1, name: 'Routing' },
    { id: 'ops-dvr', parentId: 'ops', level: 1, name: 'Driver Coaching' },
    { id: 'main', parentId: null, level: 0, name: 'Maintenance' },
    { id: 'main-sch', parentId: 'main', level: 1, name: 'Scheduled Maintenance' },
    { id: 'main-alr', parentId: 'main', level: 1, name: 'Fault Code Alerting' },
    { id: 'com', parentId: null, level: 0, name: 'Compliance' },
  ],
  processes: [
    { id: 'p-act', capabilityId: 'tel-dev', name: 'Device activation', systemIds: ['wo-cloud'] },
    { id: 'p-stream', capabilityId: 'tel-str', name: 'Telematics stream parsing', systemIds: ['wo-cloud', 'geotab'] },
    { id: 'p-multi-stop', capabilityId: 'ops-rou', name: 'Multi-stop routing', systemIds: ['wo-cloud'] },
    { id: 'p-driver-card', capabilityId: 'ops-dvr', name: 'Driver scorecard', systemIds: ['wo-cloud'] },
    { id: 'p-pm', capabilityId: 'main-sch', name: 'Mileage-based PM scheduling', systemIds: ['wo-cloud'] },
    { id: 'p-fault', capabilityId: 'main-alr', name: 'Fault code alert routing', systemIds: ['wo-cloud'], tribalKnowledge: 'medium' },
    { id: 'p-hos', capabilityId: 'com', name: 'HOS audit trail', systemIds: ['eld-store'] },
  ],
  systems: [
    { id: 'wo-cloud', name: 'WheelOps Cloud', kind: 'lob', vendor: 'WheelOps' },
    { id: 'geotab', name: 'Geotab Integration', kind: 'custom', vendor: 'Geotab' },
    { id: 'eld-store', name: 'ELD Compliance Store', kind: 'custom', vendor: 'WheelOps' },
  ],
  findings: [
    {
      id: 'f-fault-rout',
      severity: 'medium',
      title: 'Fault code alerting routes inconsistently across fleet sizes',
      detail:
        'Small fleets get all fault codes to dispatcher; large fleets to maintenance. No standard tiering rule.',
      processId: 'p-fault',
      surfacedAt: '2026-04-07',
    },
  ],
  agents: [
    { id: 'a-driver-coach', name: 'Driver Coaching Agent', role: 'Surfaces coaching moments from event data', status: 'live', recentActivity: '14k coaching nudges sent', adherence: 91 },
    { id: 'a-fault-tri', name: 'Fault Code Triager', role: 'Triages fault codes by urgency', status: 'shadow', recentActivity: 'Pilot at 3 fleets; 79% concordance with maintenance', adherence: 79 },
  ],
  initiatives: [
    { id: 'i-fault-std', title: 'Standardize fault code alert routing by fleet tier', status: 'in-flight', owner: 'Audrey Ng', targetDate: '2026-09-15', valueDollars: 1_400_000, valueCycleTimeReductionPct: 25, progressPct: 45 },
  ],
};

export const LOGISTICS_SUBSIDIARIES: BeaconSubsidiary[] = [
  lanechain,
  wheelops,
];
