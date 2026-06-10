/**
 * Type definitions for the Beacon Industries demo fixtures.
 *
 * The demo runs entirely off these structures — no backend, no fetch.
 * Shapes are intentionally light-weight and demo-specific rather than
 * a strict mirror of the real Capability/Process/System domain types.
 * If a Stage 4 surface needs to feed real components (e.g. the existing
 * CapabilityDesigner), an adapter at the integration point translates
 * BeaconCapability → Capability so the fixtures stay readable.
 */

export type LifecyclePhase =
  | 'plug-in'
  | 'design'
  | 'map'
  | 'plan'
  | 'execute'
  | 'monitor'
  | 'automate';

export const LIFECYCLE_PHASE_ORDER: readonly LifecyclePhase[] = [
  'plug-in',
  'design',
  'map',
  'plan',
  'execute',
  'monitor',
  'automate',
] as const;

export const LIFECYCLE_PHASE_LABEL: Record<LifecyclePhase, string> = {
  'plug-in': 'Plug-In',
  design: 'Design',
  map: 'Map',
  plan: 'Plan',
  execute: 'Execute',
  monitor: 'Monitor',
  automate: 'Automate',
};

export type Industry =
  | 'healthcare'
  | 'government'
  | 'insurance'
  | 'instrumentation'
  | 'financial'
  | 'legal'
  | 'logistics'
  | 'education'
  | 'creative';

export const INDUSTRY_LABEL: Record<Industry, string> = {
  healthcare: 'Healthcare Software',
  government: 'Government & Public Sector',
  insurance: 'Insurance Technology',
  instrumentation: 'Specialty Instrumentation',
  financial: 'Financial Software',
  legal: 'Legal & Professional Services',
  logistics: 'Logistics & Transportation',
  education: 'Education & Campus Systems',
  creative: 'Creative Tools',
};

export type AiReadiness = 'low' | 'medium' | 'high';
export type Severity = 'low' | 'medium' | 'high';
export type TribalKnowledge = 'low' | 'medium' | 'high';

export interface BeaconCapability {
  /** Slug-like id, scoped to the parent subsidiary. */
  id: string;
  parentId: string | null;
  name: string;
  /** 0 = root, 1 = direct child, etc. */
  level: number;
  description?: string;
}

export type SystemKind =
  | 'erp'
  | 'lob'
  | 'analytics'
  | 'collaboration'
  | 'identity'
  | 'custom';

export interface BeaconSystem {
  id: string;
  name: string;
  kind: SystemKind;
  vendor?: string;
  /** Short note explaining what role this system plays for the subsidiary. */
  note?: string;
}

export interface BeaconProcess {
  id: string;
  capabilityId: string;
  name: string;
  /** Which systems back this process. */
  systemIds: string[];
  /** Used by the Environment tab to highlight knowledge concentration. */
  tribalKnowledge?: TribalKnowledge;
  /** One-liner shown in tooltips / detail panels. */
  summary?: string;
}

export interface BeaconFinding {
  id: string;
  severity: Severity;
  title: string;
  detail: string;
  capabilityId?: string;
  processId?: string;
  /**
   * ISO date (YYYY-MM-DD) when the finding was surfaced. Consumed by the
   * Operations tab to render a deterministic "surfaced N days ago" label
   * computed against the central `DEMO_CURRENT_DATE` constant. Required
   * so typecheck flags any finding missing the field.
   */
  surfacedAt: string;
}

export type AgentStatus =
  | 'live'
  | 'shadow'
  | 'paused'
  | 'intervention-required';

export interface BeaconAgent {
  id: string;
  name: string;
  /** Functional role, e.g. "Claims first-touch triage". */
  role: string;
  status: AgentStatus;
  recentActivity: string;
  /** 0–100 adherence score against the agent's playbook. */
  adherence: number;
  capabilityId?: string;
  /**
   * Provenance metadata — who deployed this agent, against what criteria,
   * on what date, with what oversight chain. Fields are optional so
   * subsidiaries that haven't yet been polished for substrate-as-governance
   * surfacing can add them incrementally; consumers (e.g. the Operations
   * tab) skip the provenance line if any of the four are missing.
   */
  deployedBy?: string;
  deployedAt?: string;
  deploymentCriteria?: string;
  oversightChain?: string[];
}

export type InitiativeStatus =
  | 'proposed'
  | 'in-flight'
  | 'complete'
  | 'stalled';

export interface BeaconInitiative {
  id: string;
  title: string;
  status: InitiativeStatus;
  owner: string;
  targetDate: string;
  valueDollars: number;
  /** 0–100, e.g. 35 = 35% cycle-time reduction target. */
  valueCycleTimeReductionPct: number;
  /**
   * Current cycle progress percentage (0-100). Required so typecheck
   * flags any initiative missing the field. Conventionally:
   * `complete` → 100, `proposed` → 0 or low single digits,
   * `in-flight` → 30-80, `stalled` → mid-range cycle started but stuck.
   */
  progressPct: number;
  summary?: string;
}

export interface BeaconSubsidiary {
  slug: string;
  name: string;
  description: string;
  industry: Industry;
  /** Short qualifier that shows next to the industry tag in the UI. */
  industryDetail: string;
  revenueMillions: number;
  headcount: number;
  hqCity: string;
  cooName: string;
  lifecyclePhase: LifecyclePhase;
  aiReadiness: AiReadiness;
  capabilities: BeaconCapability[];
  processes: BeaconProcess[];
  systems: BeaconSystem[];
  findings: BeaconFinding[];
  agents: BeaconAgent[];
  initiatives: BeaconInitiative[];
  /**
   * Count of federation invitations currently pending for this
   * subsidiary. Consumed by the Subsidiary COO portfolio context band.
   * Required so typecheck flags any subsidiary missing the field.
   */
  pendingFederationInvitations: number;
}

export type SignalTone = 'info' | 'warn' | 'alert' | 'success';

export interface BeaconCoordinationSignal {
  id: string;
  label: string;
  count: number;
  tone: SignalTone;
}

export interface BeaconCrossOpportunity {
  id: string;
  title: string;
  detail: string;
  affectedSubsidiarySlugs: string[];
  estimatedAnnualValueDollars: number;
  /**
   * Optional reference to a `BeaconContract` id. When present, the
   * opportunity card on the Operating Partner dashboard becomes a
   * click-through to the contract detail surface (governance-3). Only
   * opportunities that have been promoted to a documented coordination
   * contract carry this id; others remain non-interactive cards.
   */
  contractId?: string;
}

export interface BeaconPortfolio {
  companyName: string;
  hqCity: string;
  totalRevenueMillions: number;
  subsidiaries: BeaconSubsidiary[];
  crossPortfolioOpportunities: BeaconCrossOpportunity[];
  coordinationSignals: BeaconCoordinationSignal[];
  /**
   * Count of subsidiaries observed to have stalled in their current
   * lifecycle phase (e.g. 90+ days without movement). Surfaced on the
   * Operating Partner coordination signal strip; required so typecheck
   * forces every portfolio assembly to declare it.
   */
  lifecycleStallCount: number;
  /**
   * Count of cross-subsidiary coordination patterns the substrate
   * surfaced this week. Surfaced on the Operating Partner coordination
   * signal strip; required so typecheck forces every portfolio assembly
   * to declare it.
   */
  coordinationPatternsThisWeek: number;
}

export type CascadeLevel = {
  level: number;             // 0 = L0 originator, 1+ = downstream levels
  ownerLabel: string;        // e.g., "Operating Partner", "Curasync COO"
  state: 'looking' | 'absorbed' | 'cascaded' | 'declined';
  refinedCritique?: string;  // present when state is 'cascaded' or 'absorbed'
  declineReasoning?: string; // present when state is 'declined'
  timestamp: string;         // ISO date when this level acted
};

export type BeaconCascade = {
  id: string;
  originatedBy: string;      // L0 owner label
  originatedAt: string;      // ISO date
  originalCritique: string;  // The L0 critique, durable through the chain
  acceptanceCriteria: string;// What L0 considers a successful resolution
  currentState: 'in-flight' | 'resolved-looks-good' | 'resolved-declined';
  affectedSubsidiarySlug?: string;  // optional — links cascade to a subsidiary
  levels: CascadeLevel[];
};

export type ContractParty = {
  partyLabel: string;          // "Curasync Clinical Systems", "Beacon Corporate Operations", etc.
  commitments: string[];       // What this party agrees to do
};

export type ContractEnforcement = {
  monitor: string;             // How compliance is monitored, plain language
  monitorMechanism: string;    // The MCP-enforcement language (machine-readable in spirit)
  declineTriggers: string[];   // What conditions allow either party to invoke Decline
};

export type BeaconContract = {
  id: string;
  type: 'cross-portfolio-opportunity' | 'federation-invitation';
  title: string;               // Short title, used as card heading
  context: string;             // 1-2 sentence framing
  parties: ContractParty[];    // The parties (usually 2-N)
  enforcement: ContractEnforcement;
  estimatedValue?: string;     // Optional, for cross-portfolio opportunities ("$1.2M annually")
  linkedSubsidiarySlugs: string[];  // Subsidiaries involved, for navigation back-references
  proposedAt: string;          // ISO date
};
