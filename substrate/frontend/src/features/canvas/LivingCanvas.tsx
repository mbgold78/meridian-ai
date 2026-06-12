import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ReactFlow,
  Background,
  useNodesState,
  useEdgesState,
  useReactFlow,
  type Node,
  type Edge,
  type NodeMouseHandler,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import ELK from 'elkjs/lib/elk.bundled.js';

import { useApiQuery } from '@/shared/hooks/useApi';
import { useWorkspace } from '@/shared/hooks/useWorkspace';
import { getEcosystemGraph } from '@/api/endpoints/ecosystem';
import { getFindings } from '@/api/endpoints/findings';
import { getTransformationPlan } from '@/api/endpoints/transformation';
import { getDecisionLog } from '@/api/endpoints/decisions';
import { getThesis, getAssistantStatus } from '@/api/endpoints/intelligence';
import { LoadingSpinner, ErrorBanner } from '@/shared/components';
import type {
  EcosystemGraph,
  Finding,
  SystemEntity,
  Integration,
  Process,
  TransformationPlan,
  DesignDecision,
  ReductionThesis,
  AssistantStatus,
} from '@/shared/types/api';

import { CanvasSystemNode, type CanvasSystemNodeData } from './nodes/CanvasSystemNode';
import { CanvasProcessNode, type CanvasProcessNodeData } from './nodes/CanvasProcessNode';
import { FlowEdge } from './nodes/FlowEdge';
import { LensRail, type Lens } from './LensRail';
import { WaveScrubber } from './WaveScrubber';
import { AmbientChatBar, type AmbientSignal } from './AmbientChatBar';
import { DetailDrawer, type DrawerTab } from './drawer/DetailDrawer';
import { SummaryRibbon, type RibbonStats } from './SummaryRibbon';
import { Legend } from './Legend';
import { WelcomeOverlay } from './WelcomeOverlay';
import { CompletenessBadge } from './CompletenessBadge';
import { AddSourceModal } from './AddSourceModal';
import { DnaPill } from './DnaPill';
import { retirementMonth, processRetirementMonth } from './retirementHeuristics';

function FlowResizer() {
  const { fitView } = useReactFlow();
  useEffect(() => {
    const onResize = () => fitView({ padding: 0.18, duration: 200 });
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [fitView]);
  return null;
}

const elk = new ELK();

const nodeTypes = {
  canvasSystem: CanvasSystemNode,
  canvasProcess: CanvasProcessNode,
};

const edgeTypes = {
  flow: FlowEdge,
};

async function layoutElements(nodes: Node[], edges: Edge[]): Promise<Node[]> {
  const elkGraph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.spacing.nodeNode': '48',
      'elk.layered.spacing.nodeNodeBetweenLayers': '130',
      'elk.layered.spacing.edgeNodeBetweenLayers': '40',
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: n.type === 'canvasProcess' ? 200 : 240,
      height: n.type === 'canvasProcess' ? 72 : 88,
    })),
    edges: edges.map((e) => ({
      id: e.id,
      sources: [e.source],
      targets: [e.target],
    })),
  };

  const out = (await elk.layout(elkGraph)) as {
    children?: { id: string; x?: number; y?: number }[];
  };
  const pos = new Map<string, { x: number; y: number }>();
  for (const c of out.children ?? []) {
    pos.set(c.id, { x: c.x ?? 0, y: c.y ?? 0 });
  }
  return nodes.map((n) => ({ ...n, position: pos.get(n.id) ?? { x: 0, y: 0 } }));
}

/**
 * Living Canvas — the ecosystem map surface.
 *
 * Port deviations from homepoc (port-map §2.5 step 7 / seam S14):
 *  - Domain scope comes from {@link useWorkspace} (deploy pin → user
 *    selection → single → first-non-demo), replacing the hardcoded DOMAIN_ID
 *    constant that silently returned empty graphs against any other database.
 *  - The '$1.1M' annual-savings ribbon literal is derived from the reduction
 *    thesis (GET /intelligence/thesis) instead of being hardcoded.
 *  - Retirement-wave heuristics moved to ./retirementHeuristics (TODO(S14)).
 */
export function LivingCanvas() {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = (searchParams.get('view') ?? null) as DrawerTab;

  const [lens, setLens] = useState<Lens>('today');
  const [month, setMonth] = useState(0);
  const [tab, setTab] = useState<DrawerTab>(initialTab);
  const [spotlightSet, setSpotlightSet] = useState<Set<string>>(new Set());
  const [lastSignal, setLastSignal] = useState<AmbientSignal | null>(null);
  const [selectedSystemId, setSelectedSystemId] = useState<string | null>(null);
  const [showAddSource, setShowAddSource] = useState(false);

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);

  const workspace = useWorkspace();
  const { domainId } = workspace;
  const domainReady = workspace.status === 'ready' && !!domainId;

  const { data: graph, isLoading: loadingGraph, error } = useApiQuery<EcosystemGraph>(
    ['canvas-graph', domainId ?? 'none'],
    () => getEcosystemGraph(domainId as string),
    { enabled: domainReady }
  );
  const { data: findings } = useApiQuery<Finding[]>(
    ['canvas-findings', domainId ?? 'none'],
    () => getFindings(domainId as string),
    { enabled: domainReady }
  );
  const { data: plan } = useApiQuery<TransformationPlan>(
    ['canvas-plan', domainId ?? 'none'],
    () => getTransformationPlan(domainId as string),
    { enabled: domainReady }
  );
  const { data: decisions } = useApiQuery<DesignDecision[]>(
    ['canvas-decisions', domainId ?? 'none'],
    () => getDecisionLog(domainId as string),
    { enabled: domainReady }
  );
  const { data: thesis, isLoading: thesisLoading } = useApiQuery<ReductionThesis>(
    ['canvas-thesis', domainId ?? 'none'],
    () => getThesis(domainId as string),
    { enabled: domainReady }
  );
  const { data: assistantStatus, isLoading: assistantLoading } = useApiQuery<AssistantStatus>(
    ['canvas-assistant', domainId ?? 'none'],
    () => getAssistantStatus(domainId as string),
    { enabled: domainReady }
  );

  // Sync drawer tab to URL
  useEffect(() => {
    if (tab) {
      setSearchParams({ view: tab }, { replace: true });
    } else if (searchParams.has('view')) {
      searchParams.delete('view');
      setSearchParams(searchParams, { replace: true });
    }
  }, [tab, setSearchParams, searchParams]);

  // Index findings by system
  const findingsBySystem = useMemo(() => {
    const map = new Map<string, Finding[]>();
    for (const f of findings ?? []) {
      for (const sid of f.affectedSystemIds ?? []) {
        if (!map.has(sid)) map.set(sid, []);
        map.get(sid)!.push(f);
      }
    }
    return map;
  }, [findings]);

  // Ambient signal → spotlight the relevant set
  useEffect(() => {
    if (!lastSignal) return;
    if (lastSignal.kind === 'findings') {
      const ids = new Set<string>();
      for (const f of findings ?? []) {
        if (f.severity === 'CRITICAL' || f.severity === 'HIGH') {
          for (const sid of f.affectedSystemIds ?? []) ids.add(sid);
        }
      }
      setSpotlightSet(ids);
    } else if (lastSignal.kind === 'overlaps') {
      // Spotlight systems that share canonical entities
      const ids = new Set<string>();
      const entityToSystem = new Map<string, string>();
      for (const ce of graph?.canonicalEntities ?? []) {
        if (ce.systemId) entityToSystem.set(ce.id, ce.systemId);
      }
      for (const o of graph?.entityOverlaps ?? []) {
        const a = o.sourceEntityId ? entityToSystem.get(o.sourceEntityId) : undefined;
        const b = o.targetEntityId ? entityToSystem.get(o.targetEntityId) : undefined;
        if (a) ids.add(a);
        if (b) ids.add(b);
      }
      setSpotlightSet(ids);
    }
    // Clear spotlight after 6s
    const t = setTimeout(() => setSpotlightSet(new Set()), 6000);
    return () => clearTimeout(t);
  }, [lastSignal, findings, graph]);

  // Build canvas nodes + edges
  useEffect(() => {
    if (!graph) return;

    const systemRetired = (sys: SystemEntity): boolean => {
      const r = retirementMonth(sys);
      if (r == null) return false;
      if (lens === 'target') return true;
      if (lens === 'waves') return month >= r;
      return false;
    };

    const processRetired = (p: Process): boolean => {
      const r = processRetirementMonth(p);
      if (r == null) return false;
      if (lens === 'target') return true;
      if (lens === 'waves') return month >= r;
      return false;
    };

    const sysNodes: Node<CanvasSystemNodeData>[] = graph.systems.map((s) => {
      const fs = findingsBySystem.get(s.id) ?? [];
      const critical = fs.filter(
        (f) => f.severity === 'CRITICAL' || f.severity === 'HIGH'
      ).length;
      return {
        id: s.id,
        type: 'canvasSystem',
        position: { x: 0, y: 0 },
        data: {
          label: s.name,
          vendor: s.vendor,
          systemType: s.systemType,
          status: s.status,
          businessLine: s.businessLine,
          findingCount: fs.length,
          criticalCount: critical,
          retired: systemRetired(s),
          spotlight: spotlightSet.has(s.id),
        },
      };
    });

    const procNodes: Node<CanvasProcessNodeData>[] = (graph.processes ?? []).map((p) => ({
      id: `mp-${p.id}`,
      type: 'canvasProcess',
      position: { x: 0, y: 0 },
      data: {
        label: p.name,
        classification: p.classification,
        riskRating: p.riskRating,
        tribal: p.classification === 'TRIBAL_KNOWLEDGE',
        retired: processRetired(p),
        spotlight: false,
      },
    }));

    // Build integration edges
    const allEdges: Edge[] = graph.integrations
      .filter((i) => i.sourceSystemId && i.targetSystemId)
      .map((i: Integration) => {
        const src = graph.systems.find((s) => s.id === i.sourceSystemId);
        const tgt = graph.systems.find((s) => s.id === i.targetSystemId);
        const retired =
          (src && systemRetired(src)) || (tgt && systemRetired(tgt)) || false;
        const emphasized =
          (src && spotlightSet.has(src.id)) || (tgt && spotlightSet.has(tgt.id)) || false;
        return {
          id: `i-${i.id}`,
          source: i.sourceSystemId ?? '',
          target: i.targetSystemId ?? '',
          type: 'flow',
          data: { retired, emphasized },
        };
      });

    const allNodes: Node[] = [...sysNodes, ...procNodes];

    void layoutElements(allNodes, allEdges).then((positioned) => {
      setNodes(positioned);
      setEdges(allEdges);
    });
  }, [graph, lens, month, findingsBySystem, spotlightSet, setNodes, setEdges]);

  const handleSignal = useCallback((s: AmbientSignal) => {
    setLastSignal(s);
    // Also auto-open drawer for findings
    if (s.kind === 'findings') {
      setTab('risks');
    }
  }, []);

  const handleNodeClick = useCallback<NodeMouseHandler>((_, node) => {
    if (node.type === 'canvasSystem') {
      setSelectedSystemId(node.id);
      setTab('provenance');
    }
  }, []);

  const selectedSystem = graph?.systems.find((s) => s.id === selectedSystemId) ?? null;

  const handleSpotlightIds = useCallback((ids: string[]) => {
    setSpotlightSet(new Set(ids));
    const t = setTimeout(() => setSpotlightSet(new Set()), 8000);
    return () => clearTimeout(t);
  }, []);

  if (workspace.status === 'loading' || loadingGraph) {
    return (
      <div className="flex h-full items-center justify-center bg-background">
        <LoadingSpinner size="lg" />
      </div>
    );
  }
  if (workspace.status === 'error') {
    return (
      <div className="p-6 bg-background min-h-full">
        <ErrorBanner
          message={
            workspace.error instanceof Error
              ? workspace.error.message
              : 'Failed to resolve the workspace domain'
          }
        />
      </div>
    );
  }
  if (workspace.status === 'empty' || !domainId) {
    // First-run state: a fresh substrate deploy starts with an empty database
    // (population flows through ingestion) — not an error.
    return (
      <div className="flex h-full items-center justify-center bg-background p-6">
        <div className="max-w-md rounded-2xl border border-border bg-surface-raised/80 p-7 text-center shadow-2xl">
          <p className="text-[10px] font-bold uppercase tracking-[0.3em] text-primary">
            No workspace yet
          </p>
          <h2 className="mt-2 text-xl font-black text-foreground">
            Nothing to map — this environment has no domains.
          </h2>
          <p className="mt-3 text-[13px] leading-relaxed text-foreground-muted">
            The canvas draws the systems, flows, and manual processes of a
            workspace domain. Ingest data (or seed the demo pack in a dev
            environment) and this surface comes alive.
          </p>
        </div>
      </div>
    );
  }
  if (error) {
    return (
      <div className="p-6 bg-background min-h-full">
        <ErrorBanner message={error.message ?? 'Failed to load ecosystem'} />
      </div>
    );
  }

  const stats: RibbonStats = {
    systems: graph?.systems.length ?? 0,
    processes: graph?.processes.length ?? 0,
    findings: findings?.length ?? 0,
    highFindings:
      findings?.filter((f) => f.severity === 'HIGH' || f.severity === 'CRITICAL').length ?? 0,
    // Plan endpoint is fixture-gated (seam S4); fall back to the deterministic
    // reduction thesis, then to "no change" when neither has loaded.
    targetSystems:
      plan?.currentVsTarget.systemsAfter ?? thesis?.systemsKeep ?? (graph?.systems.length ?? 0),
    annualSavings: thesis ? `$${thesis.annualSavings}` : '—',
  };

  return (
    // data-canvas-ready reflects the EXISTING nodes.length>0 state (DOC-1 §4
    // item 1): the only deterministic wait gate for the async ELK layout.
    <div
      data-testid="living-canvas"
      data-canvas-ready={nodes.length > 0}
      className="relative h-full w-full overflow-hidden bg-background"
    >
      {/* Subtle gradient backdrop — dimmer than before so tiles read clearly */}
      <div
        className="pointer-events-none absolute inset-0"
        style={{
          background:
            'radial-gradient(ellipse at 15% 10%, hsl(var(--primary) / 0.06) 0%, transparent 45%), radial-gradient(ellipse at 85% 90%, hsl(var(--info) / 0.06) 0%, transparent 45%)',
        }}
      />

      <SummaryRibbon lens={lens} month={month} stats={stats} />

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        fitView
        fitViewOptions={{ padding: 0.18 }}
        onNodeClick={handleNodeClick}
        proOptions={{ hideAttribution: true }}
        nodesDraggable
        panOnScroll
        zoomOnScroll={false}
        minZoom={0.3}
        maxZoom={1.5}
      >
        <Background gap={32} size={1} color="hsl(var(--foreground) / 0.08)" />
        <FlowResizer />
      </ReactFlow>

      <LensRail lens={lens} onChange={setLens} />

      <DnaPill />

      <CompletenessBadge
        status={assistantStatus}
        active={tab === 'assistant'}
        onClick={() => setTab(tab === 'assistant' ? null : 'assistant')}
      />

      <DetailDrawer
        domainId={domainId}
        tab={tab}
        onTab={setTab}
        findings={findings ?? []}
        systems={graph?.systems ?? []}
        decisions={decisions ?? []}
        plan={plan}
        thesis={thesis}
        thesisLoading={thesisLoading}
        assistantStatus={assistantStatus}
        assistantLoading={assistantLoading}
        month={month}
        onScrub={(m) => {
          setLens('waves');
          setMonth(m);
        }}
        onSpotlight={handleSpotlightIds}
        selectedSystem={selectedSystem}
      />

      {lens === 'waves' && <WaveScrubber month={month} onChange={setMonth} />}

      <Legend />

      <AmbientChatBar domainId={domainId} onSignal={handleSignal} />

      {/* Add Source button — bottom-left above legend */}
      <button
        onClick={() => setShowAddSource(true)}
        className="pointer-events-auto absolute bottom-20 left-4 z-20 flex items-center gap-1.5 rounded-lg border border-border bg-surface-raised/70 px-3 py-1.5 text-[11px] font-semibold text-foreground-muted shadow backdrop-blur-xl transition-all hover:border-primary/40 hover:text-primary"
      >
        <svg className="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
          <path fillRule="evenodd" d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z" clipRule="evenodd" />
        </svg>
        Add Source
      </button>

      {showAddSource && <AddSourceModal onClose={() => setShowAddSource(false)} />}

      <WelcomeOverlay />
    </div>
  );
}
