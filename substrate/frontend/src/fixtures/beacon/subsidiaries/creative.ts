import type { BeaconSubsidiary } from '../types';

const lumenchroma: BeaconSubsidiary = {
  slug: 'lumenchroma',
  name: 'Lumenchroma',
  description:
    'Color grading and VFX pipeline software for boutique post-production studios; powers 130 finishing facilities worldwide.',
  industry: 'creative',
  industryDetail: 'Color grading & VFX pipeline',
  revenueMillions: 60,
  headcount: 140,
  hqCity: 'Burbank, CA',
  cooName: 'Shay Linder',
  lifecyclePhase: 'automate',
  aiReadiness: 'high',
  pendingFederationInvitations: 0,
  capabilities: [
    { id: 'pre', parentId: null, level: 0, name: 'Pre-Production Setup' },
    { id: 'pre-col', parentId: 'pre', level: 1, name: 'Color Pipeline Setup' },
    { id: 'pre-sty', parentId: 'pre', level: 1, name: 'Show Style Definition' },
    { id: 'prod', parentId: null, level: 0, name: 'Production Workflow' },
    { id: 'prod-dai', parentId: 'prod', level: 1, name: 'Dailies Processing' },
    { id: 'prod-rev', parentId: 'prod', level: 1, name: 'Review & Notes' },
    { id: 'fin', parentId: null, level: 0, name: 'Finishing' },
    { id: 'fin-col', parentId: 'fin', level: 1, name: 'Color Grading' },
    { id: 'fin-vfx', parentId: 'fin', level: 1, name: 'VFX Compositing' },
    { id: 'fin-del', parentId: 'fin', level: 1, name: 'Deliverables' },
    { id: 'asst', parentId: null, level: 0, name: 'Asset Management' },
    { id: 'asst-lib', parentId: 'asst', level: 1, name: 'Asset Library' },
  ],
  processes: [
    { id: 'p-aces', capabilityId: 'pre-col', name: 'ACES color pipeline setup', systemIds: ['lc-suite'] },
    { id: 'p-show-lut', capabilityId: 'pre-sty', name: 'Show LUT family generation', systemIds: ['lc-suite'] },
    { id: 'p-dailies', capabilityId: 'prod-dai', name: 'Auto-dailies render', systemIds: ['lc-suite', 'resolve'] },
    { id: 'p-review', capabilityId: 'prod-rev', name: 'Director review session', systemIds: ['frame-io'] },
    { id: 'p-grade', capabilityId: 'fin-col', name: 'Grading session capture', systemIds: ['lc-suite', 'resolve'] },
    { id: 'p-comp', capabilityId: 'fin-vfx', name: 'Comp shot tracking', systemIds: ['lc-suite'] },
    { id: 'p-deliv', capabilityId: 'fin-del', name: 'Deliverable matrix per platform', systemIds: ['lc-suite'], tribalKnowledge: 'medium' },
    { id: 'p-lib', capabilityId: 'asst-lib', name: 'Versioned asset library', systemIds: ['lc-suite'] },
  ],
  systems: [
    { id: 'lc-suite', name: 'Lumenchroma Suite', kind: 'lob', vendor: 'Lumenchroma' },
    { id: 'resolve', name: 'DaVinci Resolve Integration', kind: 'custom', vendor: 'Blackmagic' },
    { id: 'frame-io', name: 'Frame.io Connector', kind: 'collaboration', vendor: 'Adobe' },
  ],
  findings: [
    {
      id: 'f-deliv-spec',
      severity: 'medium',
      title: 'Deliverable matrix maintained manually per platform spec change',
      detail:
        'Netflix, Apple TV+, Amazon spec updates land monthly; matrix lags by 2-3 weeks per platform.',
      processId: 'p-deliv',
      surfacedAt: '2026-04-17',
    },
  ],
  agents: [
    { id: 'a-dailies-prep', name: 'Dailies Prep Automation', role: 'Preps + renders dailies overnight', status: 'live', recentActivity: 'Automated 100% of trailing 30-day dailies', adherence: 99 },
    { id: 'a-note-attr', name: 'Note Attribution Agent', role: 'Attributes review notes to shots/timecodes', status: 'live', recentActivity: '8,400 notes attributed this month', adherence: 96 },
    { id: 'a-shot-rec', name: 'Shot-Version Reconciler', role: 'Reconciles versions across editorial and finishing', status: 'live', recentActivity: '14k versions reconciled', adherence: 98 },
    { id: 'a-deliv-watch', name: 'Deliverable Spec Watcher', role: 'Watches platform spec docs; flags deltas', status: 'live', recentActivity: 'Caught 6 platform spec changes this month', adherence: 95 },
    { id: 'a-render-tune', name: 'Render Farm Queue Tuner', role: 'Tunes render queue priority by deadline', status: 'live', recentActivity: 'Cut median render wait 38%', adherence: 94 },
  ],
  initiatives: [
    { id: 'i-deliv-pub', title: 'Auto-publish deliverables to platforms', status: 'complete', owner: 'Shay Linder', targetDate: '2026-02-15', valueDollars: 2_100_000, valueCycleTimeReductionPct: 90, progressPct: 100 },
    { id: 'i-show-lut', title: 'Per-show LUT generation from scripts', status: 'in-flight', owner: 'Aria Belkin', targetDate: '2026-08-31', valueDollars: 1_400_000, valueCycleTimeReductionPct: 50, progressPct: 65 },
  ],
};

export const CREATIVE_SUBSIDIARIES: BeaconSubsidiary[] = [
  lumenchroma,
];
