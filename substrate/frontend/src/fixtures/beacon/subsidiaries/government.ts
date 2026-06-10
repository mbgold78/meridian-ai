import type { BeaconSubsidiary } from '../types';

const constructLedger: BeaconSubsidiary = {
  slug: 'constructledger',
  name: 'ConstructLedger',
  description:
    'Project management and compliance platform for federal contractors; serves 1,800 prime and sub contractors.',
  industry: 'government',
  industryDetail: 'Government contractor PM',
  revenueMillions: 320,
  headcount: 580,
  hqCity: 'Atlanta, GA',
  cooName: 'Lila Brennan',
  lifecyclePhase: 'design',
  aiReadiness: 'medium',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'bid', parentId: null, level: 0, name: 'Bid & Capture Management' },
    { id: 'bid-opp', parentId: 'bid', level: 1, name: 'Opportunity Tracking' },
    { id: 'bid-prop', parentId: 'bid', level: 1, name: 'Proposal Authoring' },
    { id: 'proj', parentId: null, level: 0, name: 'Project Execution' },
    { id: 'proj-sch', parentId: 'proj', level: 1, name: 'Scheduling' },
    { id: 'proj-cost', parentId: 'proj', level: 1, name: 'Cost Control' },
    { id: 'proj-chg', parentId: 'proj', level: 1, name: 'Change Order Management' },
    { id: 'comp', parentId: null, level: 0, name: 'Compliance' },
    { id: 'comp-far', parentId: 'comp', level: 1, name: 'FAR/DFARS Tracking' },
    { id: 'comp-sec', parentId: 'comp', level: 1, name: 'Security Documentation' },
    { id: 'fin', parentId: null, level: 0, name: 'Financial Reporting' },
    { id: 'fin-bill', parentId: 'fin', level: 1, name: 'Government Invoicing' },
  ],
  processes: [
    { id: 'p-sam-scrape', capabilityId: 'bid-opp', name: 'SAM.gov opportunity scrape', systemIds: ['cl-suite'] },
    { id: 'p-pp-lib', capabilityId: 'bid-prop', name: 'Past-performance library lookup', systemIds: ['cl-suite'], tribalKnowledge: 'medium' },
    { id: 'p-evm', capabilityId: 'proj-sch', name: 'Earned-value reporting', systemIds: ['cl-suite', 'contractorcloud'] },
    { id: 'p-cpp-var', capabilityId: 'proj-cost', name: 'CPP variance analysis', systemIds: ['contractorcloud'] },
    { id: 'p-chg-route', capabilityId: 'proj-chg', name: 'Change order approval routing', systemIds: ['cl-suite'], tribalKnowledge: 'high' },
    { id: 'p-dcaa', capabilityId: 'comp-far', name: 'DCAA compliance checklist', systemIds: ['cl-suite'], tribalKnowledge: 'high' },
    { id: 'p-fac-clr', capabilityId: 'comp-sec', name: 'Facility clearance tracking', systemIds: ['cl-suite'] },
    { id: 'p-wawf', capabilityId: 'fin-bill', name: 'Wide Area Workflow invoice submission', systemIds: ['piee'] },
  ],
  systems: [
    { id: 'cl-suite', name: 'ConstructLedger Suite', kind: 'lob', vendor: 'ConstructLedger' },
    { id: 'contractorcloud', name: 'ContractorCloud ERP', kind: 'erp', vendor: 'ContractorCloud', note: 'Customer-side ERP integration' },
    { id: 'piee', name: 'PIEE Submission Gateway', kind: 'custom', vendor: 'ConstructLedger' },
  ],
  findings: [
    {
      id: 'f-dcaa-var',
      severity: 'high',
      title: 'DCAA checklist completion varies by project manager',
      detail:
        'Same checklist exists in 4 versions across PMOs; audit findings concentrated in projects using the older two.',
      processId: 'p-dcaa',
      surfacedAt: '2026-03-22',
    },
    {
      id: 'f-chg-paths',
      severity: 'medium',
      title: 'Change-order routing has 4 different approval paths',
      detail:
        'Routing rules diverged after 2021 acquisition; consolidating to one would eliminate ~40% of routing exceptions.',
      processId: 'p-chg-route',
      surfacedAt: '2026-04-02',
    },
  ],
  agents: [
    { id: 'a-opp-fit', name: 'Opportunity Fit Scorer', role: 'Scores SAM.gov opportunities for bid/no-bid', status: 'live', recentActivity: '380 opportunities scored this week', adherence: 92 },
    { id: 'a-pp-write', name: 'Past-Performance Writer', role: 'Drafts past-performance narratives', status: 'shadow', recentActivity: '32 drafts; 78% accepted with light edit', adherence: 78 },
    { id: 'a-comp-gap', name: 'Compliance Gap Detector', role: 'Scans projects for missing FAR artifacts', status: 'live', recentActivity: '14 gaps surfaced this week', adherence: 91 },
  ],
  initiatives: [
    { id: 'i-chg-std', title: 'Standardize change-order routing to one path', status: 'proposed', owner: 'Lila Brennan', targetDate: '2026-12-15', valueDollars: 1_900_000, valueCycleTimeReductionPct: 35, progressPct: 5 },
    { id: 'i-pp-auto', title: 'Auto-draft past-performance from CRM', status: 'in-flight', owner: 'Marc Tilden', targetDate: '2026-09-15', valueDollars: 2_400_000, valueCycleTimeReductionPct: 65, progressPct: 60 },
  ],
};

const atlasGis: BeaconSubsidiary = {
  slug: 'atlas-gis',
  name: 'AtlasGIS Public',
  description:
    'GIS platform for municipal and county governments; powers public works, permitting, and CAD/911 integration in 1,200 jurisdictions.',
  industry: 'government',
  industryDetail: 'Municipal GIS',
  revenueMillions: 250,
  headcount: 470,
  hqCity: 'Denver, CO',
  cooName: 'Sven Holm',
  lifecyclePhase: 'map',
  aiReadiness: 'medium',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'data', parentId: null, level: 0, name: 'Geospatial Data Management' },
    { id: 'data-acq', parentId: 'data', level: 1, name: 'Data Acquisition' },
    { id: 'data-cur', parentId: 'data', level: 1, name: 'Layer Curation' },
    { id: 'app', parentId: null, level: 0, name: 'Citizen-Facing Apps' },
    { id: 'app-perm', parentId: 'app', level: 1, name: 'Permit Maps' },
    { id: 'app-work', parentId: 'app', level: 1, name: 'Public Works Tracker' },
    { id: 'gov', parentId: null, level: 0, name: 'Government Workflows' },
    { id: 'gov-addr', parentId: 'gov', level: 1, name: 'Address Authority' },
    { id: 'gov-park', parentId: 'gov', level: 1, name: 'Parks & Right-of-Way' },
    { id: 'integ', parentId: null, level: 0, name: 'System Integrations' },
    { id: 'integ-cad', parentId: 'integ', level: 1, name: 'CAD/911 Bridge' },
  ],
  processes: [
    { id: 'p-aerial', capabilityId: 'data-acq', name: 'Aerial imagery ingest', systemIds: ['ag-server'] },
    { id: 'p-parcel', capabilityId: 'data-cur', name: 'Parcel polygon updates', systemIds: ['ag-server', 'esri'] },
    { id: 'p-perm-overlay', capabilityId: 'app-perm', name: 'Permit overlay refresh', systemIds: ['ag-server'] },
    { id: 'p-snowplow', capabilityId: 'app-work', name: 'Snowplow status feed', systemIds: ['cityworks'] },
    { id: 'p-addr-auth', capabilityId: 'gov-addr', name: 'Address geocode authority', systemIds: ['ag-server'], tribalKnowledge: 'medium' },
    { id: 'p-park-inv', capabilityId: 'gov-park', name: 'Park asset inventory', systemIds: ['cityworks'] },
    { id: 'p-cad-push', capabilityId: 'integ-cad', name: 'CAD incident push', systemIds: ['ag-server'] },
  ],
  systems: [
    { id: 'ag-server', name: 'AtlasGIS Server', kind: 'lob', vendor: 'AtlasGIS' },
    { id: 'esri', name: 'Esri ArcGIS Enterprise', kind: 'custom', vendor: 'Esri', note: 'Underlying GIS engine' },
    { id: 'cityworks', name: 'Cityworks Integration', kind: 'custom', vendor: 'AtlasGIS' },
  ],
  findings: [
    {
      id: 'f-addr-prop',
      severity: 'high',
      title: 'Address authority changes propagate inconsistently to CAD/911',
      detail:
        'Dispatch can be routing to a stale parcel boundary up to 36h after a city annexation. One near-miss filed in Q1.',
      processId: 'p-cad-push',
      surfacedAt: '2026-04-15',
    },
  ],
  agents: [
    { id: 'a-addr-bcast', name: 'Address Change Broadcaster', role: 'Pushes address authority diffs to subscribers', status: 'intervention-required', recentActivity: 'Webhook timeouts on 3 customer endpoints', adherence: 73 },
    { id: 'a-perm-rebuild', name: 'Permit Map Rebuilder', role: 'Rebuilds permit overlays nightly', status: 'live', recentActivity: '1,180 jurisdictions rebuilt last night', adherence: 99 },
  ],
  initiatives: [
    { id: 'i-single-addr', title: 'Single address authority across all city systems', status: 'in-flight', owner: 'Sven Holm', targetDate: '2026-10-01', valueDollars: 3_200_000, valueCycleTimeReductionPct: 80, progressPct: 50 },
  ],
};

const civicore: BeaconSubsidiary = {
  slug: 'civicore',
  name: 'Civicore',
  description:
    'Citizen services portal and case management for state and local agencies; deployed in 88 government bodies.',
  industry: 'government',
  industryDetail: 'Citizen services portal',
  revenueMillions: 155,
  headcount: 360,
  hqCity: 'Sacramento, CA',
  cooName: 'Ethan Marquez',
  lifecyclePhase: 'plug-in',
  aiReadiness: 'low',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'port', parentId: null, level: 0, name: 'Citizen Portal' },
    { id: 'port-acc', parentId: 'port', level: 1, name: 'Account Management' },
    { id: 'port-req', parentId: 'port', level: 1, name: 'Service Requests' },
    { id: 'case', parentId: null, level: 0, name: 'Case Management' },
    { id: 'case-rout', parentId: 'case', level: 1, name: 'Routing & Assignment' },
    { id: 'case-sla', parentId: 'case', level: 1, name: 'SLA Tracking' },
    { id: 'pay', parentId: null, level: 0, name: 'Payment Acceptance' },
    { id: 'rep', parentId: null, level: 0, name: 'Reporting to State' },
  ],
  processes: [
    { id: 'p-self-reg', capabilityId: 'port-acc', name: 'Citizen self-registration', systemIds: ['cc-engage'] },
    { id: 'p-req-intake', capabilityId: 'port-req', name: 'Service request intake', systemIds: ['cc-engage'] },
    { id: 'p-rout-rules', capabilityId: 'case-rout', name: 'Department routing rules', systemIds: ['cc-engage'], tribalKnowledge: 'high' },
    { id: 'p-sla-esc', capabilityId: 'case-sla', name: 'SLA breach escalation', systemIds: ['cc-engage'] },
    { id: 'p-pay-recon', capabilityId: 'pay', name: 'Payment reconciliation', systemIds: ['stripe-gov'] },
    { id: 'p-state-rep', capabilityId: 'rep', name: 'State performance report', systemIds: ['cc-engage'] },
  ],
  systems: [
    { id: 'cc-engage', name: 'Civicore Engage', kind: 'lob', vendor: 'Civicore' },
    { id: 'stripe-gov', name: 'Stripe for Government', kind: 'custom', vendor: 'Stripe' },
    { id: 'entra', name: 'Microsoft Entra ID', kind: 'identity', vendor: 'Microsoft' },
  ],
  findings: [
    {
      id: 'f-rout-dup',
      severity: 'high',
      title: 'Routing rules duplicated across 6 departments with conflicts',
      detail:
        'Same intake category routes to 2 different teams in Public Works vs. Code Enforcement; 12% of cases bounce.',
      processId: 'p-rout-rules',
      surfacedAt: '2026-03-04',
    },
  ],
  agents: [
    { id: 'a-req-class', name: 'Service Request Classifier', role: 'Maps free-text intake to request types', status: 'shadow', recentActivity: 'Shadow run since 2026-04-01; 84% match', adherence: 84 },
  ],
  initiatives: [
    { id: 'i-meridian-standup', title: 'Stand up Meridian capability model', status: 'in-flight', owner: 'Ethan Marquez', targetDate: '2026-06-15', valueDollars: 0, valueCycleTimeReductionPct: 0, progressPct: 30 },
  ],
};

export const GOVERNMENT_SUBSIDIARIES: BeaconSubsidiary[] = [
  constructLedger,
  atlasGis,
  civicore,
];
