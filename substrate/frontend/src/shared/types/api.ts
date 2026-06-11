// Response envelope
export interface ApiResponse<T> {
  data: T;
  meta: ApiMeta;
  errors: ApiError[] | null;
}

export interface ApiMeta {
  timestamp: string;
  requestId: string;
  version: string;
  personaLevel: string;
}

export interface ApiError {
  code: string;
  message: string;
  field: string | null;
}

// Domain types
export interface Domain {
  id: string;
  name: string;
  description: string | null;
  taxonomyConfig: Record<string, unknown> | null;
  regulatoryContext: Record<string, unknown> | null;
  metadata: Record<string, unknown> | null;
  /**
   * Demo-workspace flag. Backend field is {@code isDemo} on the entity, but
   * Jackson serializes the {@code isDemo()} boolean getter as {@code "demo"}
   * — this is the wire name, do not "fix" it to isDemo. Workspace resolution
   * prefers non-demo domains (see shared/hooks/useWorkspace.ts).
   */
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SystemEntity {
  id: string;
  domainId: string;
  name: string;
  vendor: string | null;
  description: string | null;
  systemType: string | null;
  deployedYear: number | null;
  status: string | null;
  businessLine: string | null;
  metadata: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}

export interface CanonicalEntity {
  id: string;
  domainId: string | null;
  systemId: string | null;
  name: string;
  entityType: string;
  description: string | null;
  attributes: Record<string, unknown> | null;
  executionMode: string | null;
  processId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Integration {
  id: string;
  domainId: string | null;
  sourceSystemId: string | null;
  targetSystemId: string | null;
  name: string | null;
  integrationType: string | null;
  integrationStatus: string | null;
  dataElements: Record<string, unknown> | null;
  frequency: string | null;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface EntityOverlap {
  id: string;
  domainId: string | null;
  sourceEntityId: string | null;
  targetEntityId: string | null;
  overlapType: string | null;
  confidence: number | null;
  details: Record<string, unknown> | null;
  aiInferred: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface OrganizationMember {
  id: string;
  domainId: string | null;
  name: string;
  title: string | null;
  department: string | null;
  businessLine: string | null;
  reportsTo: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Process {
  id: string;
  domainId: string | null;
  name: string;
  description: string;
  classification: string;
  frequency: string | null;
  primaryOwner: string | null;
  businessLine: string | null;
  defenseLine: string | null;
  documentationQuality: string | null;
  estimatedFte: number | null;
  riskRating: string | null;
  supportingArtifacts: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}

export interface Finding {
  id: string;
  domainId: string | null;
  findingType: string;
  severity: string | null;
  status: string | null;
  title: string;
  description: string;
  affectedSystemIds: string[] | null;
  affectedProcessIds: string[] | null;
  evidence: Record<string, unknown> | null;
  recommendation: string | null;
  aiGenerated: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface DesignDecision {
  id: string;
  domainId: string | null;
  decisionType: string;
  status: string;
  title: string;
  description: string;
  rationale: string | null;
  /**
   * JSON object on the wire (the entity field is a Jackson JsonNode —
   * e.g. {fteSaved, annualSavings, note} in the pinned hash vectors), never
   * a string. Rendering it as a string child would crash React.
   */
  impactSummary: Record<string, unknown> | null;
  affectedSystemIds: string[] | null;
  affectedProcessIds: string[] | null;
  previousHash: string | null;
  currentHash: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChatInteraction {
  id: string;
  domainId: string | null;
  personaId: string | null;
  userMessage: string | null;
  assistantMessage: string | null;
  toolCalls: Record<string, unknown> | null;
  inputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number | null;
  costEstimate: number | null;
  createdAt: string;
}

export interface EcosystemGraph {
  systems: SystemEntity[];
  integrations: Integration[];
  canonicalEntities: CanonicalEntity[];
  entityOverlaps: EntityOverlap[];
  processes: Process[];
}

export interface HashChainVerification {
  valid: boolean;
  totalDecisions: number;
  verifiedLinks: number;
  /**
   * Identifier of the FIRST decision with a broken link — the backend record
   * field is a String (the decision's UUID, title fallback), never a
   * number/index. Doctors only ever saw `null` (intact chains), which
   * satisfied both types — hence the drift.
   */
  firstBrokenAt: string | null;
  message: string;
}

export interface CostSummary {
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCostEstimate: number;
  requestCount: number;
}

export interface ImpactAnalysisResult {
  summary: string;
  systemImpacts: SystemImpact[];
  processImpacts: ProcessImpact[];
}

export interface SystemImpact {
  systemId: string;
  systemName: string;
  impactLevel: string;
  description: string;
}

export interface ProcessImpact {
  processId: string;
  processName: string;
  impactLevel: string;
  description: string;
}

// Intelligence — reduction thesis + assistant status
export interface ReductionThesis {
  headline: string;
  subhead: string;
  systemsBefore: number;
  systemsKeep: number;
  systemsRetire: number;
  annualSavings: string;
  keepList: KeepEntry[];
  retireList: RetireEntry[];
  keyRisks: string[];
  keyPersons: KeyPerson[];
  tribalProcessCount: number;
  criticalFindingCount: number;
}

export interface KeepEntry {
  id: string;
  name: string;
  vendor: string | null;
  businessLine: string | null;
  reasons: string[];
}

export interface RetireEntry {
  id: string;
  name: string;
  vendor: string | null;
  businessLine: string | null;
  reasons: string[];
  annualSavingsUsd: number;
  absorbedBy: string | null;
}

export interface KeyPerson {
  name: string;
  processCount: number;
}

export interface AssistantStatus {
  projectName: string;
  completenessPercent: number;
  phases: AssistantPhase[];
  blockers: AssistantBlocker[];
  nextAction: string;
  readyForReduction: boolean;
}

export interface AssistantPhase {
  name: string;
  subtitle: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE';
  checks: AssistantCheck[];
}

export interface AssistantCheck {
  label: string;
  passed: boolean;
  gapDescription: string | null;
}

export interface AssistantBlocker {
  id: string;
  title: string;
  description: string;
  actionLabel: string;
  actionTab: string | null;
}

// Transformation Plan (mockup)
export interface TransformationPlan {
  executiveSummary: {
    currentState: string;
    targetState: string;
    transformationArc: string;
  };
  currentVsTarget: {
    systemsBefore: number;
    systemsAfter: number;
    rolesBefore: number;
    rolesAfter: number;
    annualOperatingCostBefore: string;
    annualOperatingCostAfter: string;
    consolidations: string[];
  };
  initiatives: Initiative[];
  waveRoadmap: {
    waves: Wave[];
  };
  regulatoryTouchpoints: RegulatoryTouchpoint[];
  organizationalImpact: OrgImpact[];
  policyUpdates: PolicyUpdate[];
  decisionProvenance: {
    decisionCount: number;
    message: string;
  };
}

export interface Initiative {
  id: string;
  name: string;
  category: string;
  description: string;
  linkedDecisions: string[];
  complexity: string;
  estimatedMonths: number;
  wave: string;
  owner: string;
  raciSummary: string;
}

export interface Wave {
  name: string;
  startMonth: number;
  endMonth: number;
  initiatives: string[];
}

export interface RegulatoryTouchpoint {
  regulation: string;
  relevance: string;
  impactedInitiatives: string[];
  requiredActions: string[];
}

export interface OrgImpact {
  impactArea: string;
  description: string;
  affectedRoles: string[];
  timeline: string;
  linkedInitiatives: string[];
}

export interface PolicyUpdate {
  policyName: string;
  currentVersion: string;
  updateReason: string;
  targetCompletionWave: string;
  linkedInitiatives: string[];
  approvalAuthority: string;
}

// Evidence chain-back
export interface Evidence {
  id: string;
  entityId: string;
  entityType: string;
  domainId: string | null;
  documentRegistryId: string | null;
  sourceFile: string | null;
  confidence: number | null;
  label: string | null;
  extractor: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentRegistry {
  id: string;
  sourceFile: string;
  sourceFileSha256: string;
  documentType: string | null;
  sourceSystem: string | null;
  ingestionStatus: string;
  createdAt: string;
  updatedAt: string;
}

export interface EvidenceChainEntry {
  evidence: Evidence;
  document: DocumentRegistry | null;
}

// HTTP Source probe
export interface ProbeResult {
  url: string;
  detected: string | null;
  title: string | null;
  confidence: number;
  endpoints: string[];
  hints: string[];
  error: string | null;
}

// Project DNA
export interface DnaConfig {
  projectName: string;
  domain: string;
  entityTypes: string[];
  primaryObjective: string;
  regulatoryContext: string[];
  reductionMode: string;
  targetHorizonMonths: string;
}
