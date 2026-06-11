import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  Broadcast,
  CaretDown,
  CaretUp,
  CaretUpDown,
  Clock,
  GitBranch,
  Sparkle,
  Warning,
} from '@phosphor-icons/react';
import {
  BEACON_PORTFOLIO,
  CROSS_PORTFOLIO_OPPORTUNITIES,
  INDUSTRY_LABEL,
  LIFECYCLE_PHASE_LABEL,
  LIFECYCLE_PHASE_ORDER,
} from '@/fixtures/beacon';
import type {
  BeaconCrossOpportunity,
  BeaconSubsidiary,
  Industry,
  LifecyclePhase,
} from '@/fixtures/beacon';
import type { CascadeAttentionSignalWire } from '@/api/endpoints';
import { cn } from '@/lib/utils';
import { attentionOrder, useBeaconCascades } from './cascadeData';
import type { CascadeGridRow } from './cascadeData';
import { portfolioPaths } from './paths';

/**
 * Corporate Operating Partner dashboard — the Beacon surfaces' marquee
 * view (ported from homepoc under the product route namespace, chunk C9).
 * Subsidiaries/opportunities/signals still read from the fixture swap
 * point in `@/fixtures/beacon` (Phase 2–4 live swap pending). The cascade
 * grid is LIVE as of the Phase 2 read-model swap: rows come from
 * `useBeaconCascades()` (./cascadeData — GET /api/v1/cascades + adapter),
 * with the fixture cascades as the deterministic degraded/demo fallback.
 *
 * Five composition zones, top-to-bottom, per Stage 3 spec:
 *   1. Portfolio header strip (Instrument Serif display + live indicator).
 *   2. Coordination signal strip (4 stat cards).
 *   3. Lifecycle phase columns (Option A — 7 columns of subsidiary chips).
 *   4. Cross-portfolio opportunity grid ("Where coordination is failing").
 *   5. Subsidiary roster table (compact, sortable, with mobile card fallback).
 *
 * The visual register follows docs/design/visual-foundation.md:
 *   - warm neutrals, surface-elevation tiers (no shadows)
 *   - amber primary reserved; industry chips use --industry-* tokens
 *   - Phosphor icons regular weight; compact density on Zone 5 only
 *   - decorative animation limited to the live-dot pulse and a 100ms
 *     hover state on opportunity cards.
 */

const INDUSTRY_BORDER_LEFT: Record<Industry, string> = {
  healthcare: 'border-l-industry-healthcare',
  government: 'border-l-industry-government',
  insurance: 'border-l-industry-insurance',
  instrumentation: 'border-l-industry-instrumentation',
  financial: 'border-l-industry-financial',
  legal: 'border-l-industry-legal',
  logistics: 'border-l-industry-logistics',
  education: 'border-l-industry-education',
  creative: 'border-l-industry-creative',
};

const PHASE_BORDER_LEFT: Record<LifecyclePhase, string> = {
  'plug-in': 'border-l-phase-plug-in',
  design:    'border-l-phase-design',
  map:       'border-l-phase-map',
  plan:      'border-l-phase-plan',
  execute:   'border-l-phase-execute',
  monitor:   'border-l-phase-monitor',
  automate:  'border-l-phase-automate',
};

const PHASE_BORDER_BOTTOM: Record<LifecyclePhase, string> = {
  'plug-in': 'border-b-phase-plug-in/40',
  design:    'border-b-phase-design/40',
  map:       'border-b-phase-map/40',
  plan:      'border-b-phase-plan/40',
  execute:   'border-b-phase-execute/40',
  monitor:   'border-b-phase-monitor/40',
  automate:  'border-b-phase-automate/40',
};

// Low-alpha tint of the phase token used for the PhasePill body fill in
// the subsidiary roster. Pairs with PHASE_TEXT to tie the pill body to
// the per-phase column story (Zone 3) — parallel to how
// `bg-warning-surface text-warning` reads on warning chips. The 2px left
// accent (PHASE_BORDER_LEFT) stays as the load-bearing categorical signal.
const PHASE_BG: Record<LifecyclePhase, string> = {
  'plug-in': 'bg-phase-plug-in/10',
  design:    'bg-phase-design/10',
  map:       'bg-phase-map/10',
  plan:      'bg-phase-plan/10',
  execute:   'bg-phase-execute/10',
  monitor:   'bg-phase-monitor/10',
  automate:  'bg-phase-automate/10',
};

const PHASE_TEXT: Record<LifecyclePhase, string> = {
  'plug-in': 'text-phase-plug-in',
  design:    'text-phase-design',
  map:       'text-phase-map',
  plan:      'text-phase-plan',
  execute:   'text-phase-execute',
  monitor:   'text-phase-monitor',
  automate:  'text-phase-automate',
};

function formatRevenueMillions(millions: number): string {
  return millions >= 1000
    ? `$${(millions / 1000).toFixed(1)}B`
    : `$${millions}M`;
}

function formatDollarMillions(dollars: number): string {
  return `$${(dollars / 1_000_000).toFixed(1)}M`;
}

type SortColumn =
  | 'name'
  | 'phase'
  | 'capabilities'
  | 'processes'
  | 'agents'
  | 'findings'
  | 'revenue';

type SortDirection = 'asc' | 'desc';

interface SortState {
  col: SortColumn;
  dir: SortDirection;
}

function compareSubs(
  a: BeaconSubsidiary,
  b: BeaconSubsidiary,
  col: SortColumn,
): number {
  switch (col) {
    case 'name':
      return a.name.localeCompare(b.name);
    case 'phase':
      return (
        LIFECYCLE_PHASE_ORDER.indexOf(a.lifecyclePhase) -
        LIFECYCLE_PHASE_ORDER.indexOf(b.lifecyclePhase)
      );
    case 'capabilities':
      return a.capabilities.length - b.capabilities.length;
    case 'processes':
      return a.processes.length - b.processes.length;
    case 'agents':
      return a.agents.length - b.agents.length;
    case 'findings':
      return a.findings.length - b.findings.length;
    case 'revenue':
      return a.revenueMillions - b.revenueMillions;
  }
}

export function CorporateOperatingPartnerView() {
  const subsidiaries = BEACON_PORTFOLIO.subsidiaries;
  // Cascade read-model swap (engine-spec §11): live API rows when the
  // backend answers, deterministic fixture fallback when it doesn't.
  const { rows: cascadeRows } = useBeaconCascades();

  const interventionAgents = useMemo(
    () =>
      subsidiaries.reduce(
        (sum, s) =>
          sum + s.agents.filter((a) => a.status === 'intervention-required').length,
        0,
      ),
    [subsidiaries],
  );
  const totalAgents = useMemo(
    () => subsidiaries.reduce((sum, s) => sum + s.agents.length, 0),
    [subsidiaries],
  );
  const subsByPhase = useMemo(() => {
    const map = new Map<LifecyclePhase, BeaconSubsidiary[]>();
    for (const phase of LIFECYCLE_PHASE_ORDER) map.set(phase, []);
    for (const s of subsidiaries) map.get(s.lifecyclePhase)!.push(s);
    return map;
  }, [subsidiaries]);
  const activePhaseCount = useMemo(
    () => Array.from(subsByPhase.values()).filter((bucket) => bucket.length > 0).length,
    [subsByPhase],
  );
  const industryCount = useMemo(
    () => new Set(subsidiaries.map((s) => s.industry)).size,
    [subsidiaries],
  );
  const totalRevenueB = (BEACON_PORTFOLIO.totalRevenueMillions / 1000).toFixed(1);

  const [sort, setSort] = useState<SortState>({ col: 'name', dir: 'asc' });
  const sortedSubs = useMemo(() => {
    const copy = [...subsidiaries];
    copy.sort((a, b) => {
      const cmp = compareSubs(a, b, sort.col);
      return sort.dir === 'asc' ? cmp : -cmp;
    });
    return copy;
  }, [subsidiaries, sort]);

  return (
    <div className="mx-auto max-w-[1440px]">
      <PortfolioHeader
        subsidiaryCount={subsidiaries.length}
        industryCount={industryCount}
        totalRevenueB={totalRevenueB}
        activePhaseCount={activePhaseCount}
      />

      <div className="space-y-12 px-8 py-8">
        <CoordinationSignalStrip
          crossOppCount={CROSS_PORTFOLIO_OPPORTUNITIES.length}
          interventionCount={interventionAgents}
          totalAgents={totalAgents}
          lifecycleStallCount={BEACON_PORTFOLIO.lifecycleStallCount}
          coordinationPatternsThisWeek={
            BEACON_PORTFOLIO.coordinationPatternsThisWeek
          }
        />

        <LifecyclePhaseDistribution subsByPhase={subsByPhase} />

        <CrossPortfolioOpportunities opportunities={CROSS_PORTFOLIO_OPPORTUNITIES} />

        <IssuedCascades rows={cascadeRows} />

        <SubsidiaryRoster
          subs={sortedSubs}
          sort={sort}
          onSort={setSort}
        />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Zone 1 — Header strip                                              */
/* ------------------------------------------------------------------ */

interface PortfolioHeaderProps {
  subsidiaryCount: number;
  industryCount: number;
  totalRevenueB: string;
  activePhaseCount: number;
}

function PortfolioHeader({
  subsidiaryCount,
  industryCount,
  totalRevenueB,
  activePhaseCount,
}: PortfolioHeaderProps) {
  return (
    <header className="border-b border-border px-8 py-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="font-serif text-[36px] font-semibold leading-[40px] -tracking-[0.01em] text-foreground">
            Beacon Industries Portfolio
          </h1>
          <p className="mt-2 text-base text-foreground-muted">
            {subsidiaryCount} subsidiaries · {industryCount} industries · ${totalRevenueB}B annual revenue · {activePhaseCount} lifecycle phases active
          </p>
        </div>
        <div
          className="flex items-center gap-2 text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-subtle"
          aria-label="Illustrative demo data"
          title="Every figure on this screen is illustrative fixture data for demonstration — not a live feed."
        >
          <span className="h-2 w-2 rounded-full bg-foreground-subtle/60" aria-hidden />
          Illustrative demo data
        </div>
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------ */
/*  Zone 2 — Coordination signal strip                                 */
/* ------------------------------------------------------------------ */

interface CoordinationSignalStripProps {
  crossOppCount: number;
  interventionCount: number;
  totalAgents: number;
  lifecycleStallCount: number;
  coordinationPatternsThisWeek: number;
}

function CoordinationSignalStrip({
  crossOppCount,
  interventionCount,
  totalAgents,
  lifecycleStallCount,
  coordinationPatternsThisWeek,
}: CoordinationSignalStripProps) {
  return (
    <section
      aria-label="Coordination signals"
      className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4"
    >
      <CoordinationSignalCard
        icon={<GitBranch size={24} aria-hidden weight="regular" />}
        label="Cross-portfolio opportunities"
        value={crossOppCount}
        description="Capabilities being rebuilt across subs"
        active
      />
      <CoordinationSignalCard
        icon={<Warning size={24} aria-hidden weight="regular" />}
        label="Agents in intervention"
        value={interventionCount}
        description={`Of ${totalAgents} active across portfolio`}
        active
      />
      <CoordinationSignalCard
        icon={<Clock size={24} aria-hidden weight="regular" />}
        label="Lifecycle stalls"
        value={lifecycleStallCount}
        description="Subsidiaries 90+ days in current phase"
        active
      />
      <CoordinationSignalCard
        icon={<Broadcast size={24} aria-hidden weight="regular" />}
        label="Coordination signals"
        value={coordinationPatternsThisWeek}
        description="Cross-sub patterns AI surfaced this week"
        active
      />
    </section>
  );
}

interface CoordinationSignalCardProps {
  icon: React.ReactNode;
  label: string;
  value: number;
  description: string;
  active: boolean;
}

function CoordinationSignalCard({
  icon,
  label,
  value,
  description,
  active,
}: CoordinationSignalCardProps) {
  return (
    <article className="rounded-md border border-border bg-surface p-6">
      <div className="flex items-start justify-between gap-3">
        <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
          {label}
        </span>
        <span className={active ? 'text-primary' : 'text-foreground-muted'}>
          {icon}
        </span>
      </div>
      <div
        className="mt-4 font-serif text-[36px] font-semibold leading-[40px] -tracking-[0.01em] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {value}
      </div>
      <p className="mt-2 text-[13px] leading-[18px] text-foreground-muted">
        {description}
      </p>
    </article>
  );
}

/* ------------------------------------------------------------------ */
/*  Zone 3 — Lifecycle phase distribution (Option A)                   */
/* ------------------------------------------------------------------ */

interface LifecyclePhaseDistributionProps {
  subsByPhase: Map<LifecyclePhase, BeaconSubsidiary[]>;
}

function LifecyclePhaseDistribution({
  subsByPhase,
}: LifecyclePhaseDistributionProps) {
  return (
    <section aria-label="Lifecycle phase distribution">
      <header className="mb-4">
        <h2 className="text-[18px] font-semibold leading-[24px] text-foreground">
          Where each subsidiary sits
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          Every operating company plotted by current Meridian lifecycle phase.
        </p>
      </header>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-7">
        {LIFECYCLE_PHASE_ORDER.map((phase) => (
          <LifecyclePhaseColumn
            key={phase}
            phase={phase}
            subs={subsByPhase.get(phase) ?? []}
          />
        ))}
      </div>
    </section>
  );
}

interface LifecyclePhaseColumnProps {
  phase: LifecyclePhase;
  subs: BeaconSubsidiary[];
}

function LifecyclePhaseColumn({ phase, subs }: LifecyclePhaseColumnProps) {
  return (
    <div
      className={cn(
        'flex flex-col gap-3 rounded-md border border-border border-l-2 bg-surface p-4',
        PHASE_BORDER_LEFT[phase],
      )}
    >
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
          {LIFECYCLE_PHASE_LABEL[phase]}
        </span>
        <span
          className="text-xs text-foreground-subtle"
          style={{ fontFeatureSettings: '"tnum"' }}
        >
          {subs.length}
        </span>
      </div>
      {subs.length === 0 ? (
        <p className="text-xs italic text-foreground-subtle">No subsidiaries</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {subs.map((sub) => (
            <li key={sub.slug}>
              <SubsidiaryChip sub={sub} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface SubsidiaryChipProps {
  sub: BeaconSubsidiary;
  /** Compact variant used inside opportunity cards. */
  compact?: boolean;
}

function SubsidiaryChip({ sub, compact = false }: SubsidiaryChipProps) {
  return (
    <Link
      to={portfolioPaths.subsidiary(sub.slug)}
      className={cn(
        'block rounded-md border border-border border-l-2 bg-surface-raised text-foreground transition-colors duration-100 hover:border-border-strong',
        INDUSTRY_BORDER_LEFT[sub.industry],
        compact ? 'px-2 py-1 text-xs' : 'px-3 py-2 text-[13px] leading-[18px]',
      )}
      title={INDUSTRY_LABEL[sub.industry]}
    >
      {sub.name}
    </Link>
  );
}

/* ------------------------------------------------------------------ */
/*  Zone 4 — Cross-portfolio opportunities                             */
/* ------------------------------------------------------------------ */

interface CrossPortfolioOpportunitiesProps {
  opportunities: BeaconCrossOpportunity[];
}

function CrossPortfolioOpportunities({
  opportunities,
}: CrossPortfolioOpportunitiesProps) {
  return (
    <section aria-label="Cross-portfolio opportunities">
      <header className="mb-6">
        <h2 className="text-[22px] font-semibold leading-[28px] -tracking-[0.005em] text-foreground">
          Where coordination is failing
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          Patterns the platform has surfaced across subsidiaries this quarter.
        </p>
      </header>
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        {opportunities.map((opp) => (
          <OpportunityCard key={opp.id} opp={opp} />
        ))}
      </div>
    </section>
  );
}

interface OpportunityCardProps {
  opp: BeaconCrossOpportunity;
}

function OpportunityCard({ opp }: OpportunityCardProps) {
  const navigate = useNavigate();
  const subs = opp.affectedSubsidiarySlugs
    .map((slug) => BEACON_PORTFOLIO.subsidiaries.find((s) => s.slug === slug))
    .filter((s): s is BeaconSubsidiary => Boolean(s));

  const inner = (
    <>
      <div className="flex items-center gap-1.5">
        <Sparkle size={16} weight="regular" aria-hidden className="text-primary" />
        <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-primary">
          AI-Surfaced Opportunity
        </span>
      </div>
      <h3 className="mt-3 text-[18px] font-semibold leading-[24px] text-foreground">
        {opp.title}
      </h3>
      <p className="mt-2 text-sm leading-[20px] text-foreground">{opp.detail}</p>
      {/*
        Chip wrap rule: max 3 chips per row, regardless of total chip
        count. Each chip gets a flex-basis of (100% - 2 * gap) / 3 with
        `grow-0` so chips never stretch past one third, and the row
        always fits exactly three before wrapping. 5 chips render as
        3-then-2; 4 chips as 3-then-1; ≤3 chips share a single row.

        The wrapper `<div>` also stops click-propagation so a chip click
        navigates to the subsidiary detail without also triggering the
        outer card's contract-detail navigation.
      */}
      <div className="mt-4 flex flex-wrap gap-2">
        {subs.map((sub) => (
          <div
            key={sub.slug}
            className="grow-0 basis-[calc((100%-1rem)/3)]"
            onClick={(e) => e.stopPropagation()}
          >
            <SubsidiaryChip sub={sub} compact />
          </div>
        ))}
      </div>
      <p className="mt-auto pt-4 text-[13px] font-medium leading-[18px] text-foreground-muted">
        Estimated impact:{' '}
        <span style={{ fontFeatureSettings: '"tnum"' }}>
          {formatDollarMillions(opp.estimatedAnnualValueDollars)}
        </span>{' '}
        annually
      </p>
    </>
  );

  // When the opportunity has been promoted to a documented coordination
  // contract (governance-3), the card becomes a click-through to the
  // contract detail surface. The outer click target is a `<div
  // role="button">` rather than an `<a>` so the inner `<SubsidiaryChip>`
  // anchors remain valid (HTML disallows nested `<a>` elements).
  // Cards without a contractId remain non-interactive — they still
  // surface the opportunity but the substrate has not yet attached an
  // enforceable contract to them.
  if (opp.contractId) {
    const contractId = opp.contractId;
    const goToContract = () => navigate(portfolioPaths.contract(contractId));
    return (
      <div
        role="button"
        tabIndex={0}
        onClick={goToContract}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            goToContract();
          }
        }}
        aria-label={`Open coordination contract: ${opp.title}`}
        className="flex flex-col rounded-md border border-border bg-surface-raised p-6 text-left transition-colors duration-100 hover:border-border-strong cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        {inner}
      </div>
    );
  }
  return (
    <article className="flex flex-col rounded-md border border-border bg-surface-raised p-6 transition-colors duration-100 hover:border-border-strong">
      {inner}
    </article>
  );
}

/* ------------------------------------------------------------------ */
/*  Zone 4.5 — Cascades you've issued                                  */
/* ------------------------------------------------------------------ */

interface IssuedCascadesProps {
  rows: CascadeGridRow[];
}

function IssuedCascades({ rows }: IssuedCascadesProps) {
  // Operator attention order (engine-spec §11 item 4): decline-pending
  // first, then overdue, then escalating by lastActivityAt desc.
  const inFlight = attentionOrder(rows.filter((r) => r.currentState === 'in-flight'));
  if (inFlight.length === 0) return null;
  return (
    <section aria-label="Cascades you've issued">
      <header className="mb-6">
        <h2 className="text-[22px] font-semibold leading-[28px] -tracking-[0.005em] text-foreground">
          Cascades you've issued
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          In-flight critiques propagating through portfolio chains of command.
        </p>
      </header>
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        {inFlight.map((row) => (
          <CascadeCard key={row.id} row={row} />
        ))}
      </div>
    </section>
  );
}

interface CascadeCardProps {
  row: CascadeGridRow;
}

function CascadeCard({ row }: CascadeCardProps) {
  const headingSummary = firstSentence(row.originalCritique);
  const originatedDate = formatCascadeDate(row.originatedAt);
  const chip = ATTENTION_SIGNAL_CHIP[row.attentionSignal];
  return (
    <article className="flex flex-col rounded-md border border-border bg-surface-raised p-6 transition-colors duration-100 hover:border-border-strong">
      <div className="flex items-center gap-1.5">
        <GitBranch size={16} weight="regular" aria-hidden className="text-primary" />
        <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-primary">
          In-flight cascade
        </span>
      </div>
      <h3 className="mt-3 text-[18px] font-semibold leading-[24px] text-foreground">
        {headingSummary}
      </h3>
      <p className="mt-2 text-[13px] leading-[18px] text-foreground-muted">
        Originated {originatedDate} · Currently at L{row.frontierDepth} ·{' '}
        {row.levelCount} level chain
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-2">
        <span
          className={cn(
            'inline-flex shrink-0 items-center rounded-md border border-transparent px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em]',
            chip.cls,
          )}
        >
          {chip.label}
        </span>
        {row.overdue && (
          <span className="inline-flex shrink-0 items-center rounded-md border border-transparent bg-warning-surface px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em] text-warning">
            Overdue
          </span>
        )}
      </div>
      <div className="mt-auto flex justify-end pt-6">
        <Link
          to={portfolioPaths.cascade(row.id)}
          className="inline-flex items-center gap-1 text-[13px] font-medium leading-[18px] text-foreground transition-colors duration-100 hover:text-primary"
        >
          Inspect cascade
          <ArrowRight size={14} weight="regular" aria-hidden />
        </Link>
      </div>
    </article>
  );
}

function firstSentence(text: string): string {
  const match = /^[\s\S]*?[.!?](?=\s|$)/.exec(text.trim());
  return match ? match[0].trim() : text.trim();
}

const CASCADE_SHORT_MONTH = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
] as const;

function formatCascadeDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!m) return iso;
  const monthIdx = Number(m[2]) - 1;
  if (monthIdx < 0 || monthIdx > 11) return iso;
  return `${CASCADE_SHORT_MONTH[monthIdx]} ${Number(m[3])} ${m[1]}`;
}

/**
 * Attention-signal chip mapping (engine-spec §11 item 3): decline-pending →
 * destructive (leader-attention, beside in-intervention agents), escalating
 * → info, absorbed → success; `overdue` is a separate warn overlay chip.
 * Terminal signals never reach the in-flight grid but the record stays
 * total. Decline is never an error — destructive = leader-attention tone.
 */
const ATTENTION_SIGNAL_CHIP: Record<
  CascadeAttentionSignalWire,
  { label: string; cls: string }
> = {
  'decline-pending': {
    label: 'Decline pending',
    cls: 'bg-destructive-surface text-destructive',
  },
  escalating: {
    label: 'Escalating',
    cls: 'bg-info-surface text-info',
  },
  absorbed: {
    label: 'Absorbed',
    cls: 'bg-success-surface text-success',
  },
  'resolved-looks-good': {
    label: 'Resolved — looks good',
    cls: 'bg-success-surface text-success',
  },
  'resolved-declined': {
    label: 'Resolved — declined',
    cls: 'bg-surface text-foreground-muted',
  },
};

/* ------------------------------------------------------------------ */
/*  Zone 5 — Subsidiary roster                                         */
/* ------------------------------------------------------------------ */

interface SubsidiaryRosterProps {
  subs: BeaconSubsidiary[];
  sort: SortState;
  onSort: (next: SortState) => void;
}

function SubsidiaryRoster({ subs, sort, onSort }: SubsidiaryRosterProps) {
  return (
    <section aria-label="Full subsidiary roster">
      <header className="mb-4">
        <h2 className="text-[18px] font-semibold leading-[24px] text-foreground">
          Full portfolio
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          All {subs.length} subsidiaries — sortable by any column.
        </p>
      </header>

      {/* Desktop / tablet: real table */}
      <div className="hidden overflow-hidden rounded-md border border-border md:block">
        <table className="w-full text-[13px]">
          <thead className="bg-surface">
            <tr>
              <SortableHeader col="name" label="Subsidiary" sort={sort} onSort={onSort} align="left" />
              <SortableHeader col="phase" label="Phase" sort={sort} onSort={onSort} align="left" />
              <SortableHeader col="capabilities" label="Capabilities" sort={sort} onSort={onSort} align="right" />
              <SortableHeader col="processes" label="Processes" sort={sort} onSort={onSort} align="right" />
              <SortableHeader col="agents" label="Agents" sort={sort} onSort={onSort} align="left" />
              <SortableHeader col="findings" label="Findings" sort={sort} onSort={onSort} align="right" />
              <SortableHeader col="revenue" label="Revenue" sort={sort} onSort={onSort} align="right" />
            </tr>
          </thead>
          <tbody>
            {subs.map((sub) => (
              <SubsidiaryRow key={sub.slug} sub={sub} />
            ))}
          </tbody>
        </table>
      </div>

      {/* Mobile: stacked card list */}
      <ul className="flex flex-col gap-3 md:hidden">
        {subs.map((sub) => (
          <li key={sub.slug}>
            <SubsidiaryMobileCard sub={sub} />
          </li>
        ))}
      </ul>
    </section>
  );
}

interface SortableHeaderProps {
  col: SortColumn;
  label: string;
  sort: SortState;
  onSort: (next: SortState) => void;
  align: 'left' | 'right';
}

function SortableHeader({ col, label, sort, onSort, align }: SortableHeaderProps) {
  const active = sort.col === col;
  const nextDir: SortDirection =
    active && sort.dir === 'asc' ? 'desc' : 'asc';
  const Icon = !active ? CaretUpDown : sort.dir === 'asc' ? CaretUp : CaretDown;
  return (
    <th
      scope="col"
      className={cn(
        'border-b border-border px-3 py-2',
        align === 'right' ? 'text-right' : 'text-left',
      )}
    >
      <button
        type="button"
        onClick={() => onSort({ col, dir: nextDir })}
        className={cn(
          'inline-flex items-center gap-1 text-[11px] font-medium uppercase tracking-[0.05em] hover:text-foreground',
          align === 'right' ? 'flex-row-reverse' : '',
          active ? 'text-foreground' : 'text-foreground-muted',
        )}
        aria-label={`Sort by ${label} ${active ? `currently ${sort.dir}` : ''}`.trim()}
      >
        <span>{label}</span>
        <Icon size={12} weight={active ? 'bold' : 'regular'} aria-hidden />
      </button>
    </th>
  );
}

interface SubsidiaryRowProps {
  sub: BeaconSubsidiary;
}

function SubsidiaryRow({ sub }: SubsidiaryRowProps) {
  const intervention = sub.agents.filter(
    (a) => a.status === 'intervention-required',
  ).length;
  const findingHot = sub.findings.length > 5;
  return (
    <tr className="border-b border-border last:border-b-0 transition-colors duration-100 hover:bg-surface">
      <td className="px-3 py-2">
        <Link
          to={portfolioPaths.subsidiary(sub.slug)}
          className="font-medium text-foreground hover:text-primary"
        >
          {sub.name}
        </Link>
        <div className="text-[11px] text-foreground-muted">
          {INDUSTRY_LABEL[sub.industry]} · {sub.industryDetail}
        </div>
      </td>
      <td className="px-3 py-2">
        <PhasePill phase={sub.lifecyclePhase} />
      </td>
      <td
        className="px-3 py-2 text-right font-mono text-[13px] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {sub.capabilities.length}
      </td>
      <td
        className="px-3 py-2 text-right font-mono text-[13px] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {sub.processes.length}
      </td>
      <td className="px-3 py-2">
        <div className="flex items-center gap-2">
          <span
            className="font-mono text-[13px] text-foreground"
            style={{ fontFeatureSettings: '"tnum"' }}
          >
            {sub.agents.length}
          </span>
          {intervention > 0 && (
            <span className="inline-flex items-center rounded-md bg-destructive-surface px-2 py-0.5 text-[11px] font-medium leading-[14px] text-destructive">
              {intervention} intervention
            </span>
          )}
        </div>
      </td>
      <td className="px-3 py-2 text-right">
        <span
          className={cn(
            'inline-block rounded-md px-2 py-0.5 font-mono text-[13px]',
            findingHot
              ? 'bg-warning-surface text-foreground'
              : 'text-foreground',
          )}
          style={{ fontFeatureSettings: '"tnum"' }}
        >
          {sub.findings.length}
        </span>
      </td>
      <td
        className="px-3 py-2 text-right font-mono text-[13px] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {formatRevenueMillions(sub.revenueMillions)}
      </td>
    </tr>
  );
}

interface PhasePillProps {
  phase: LifecyclePhase;
}

function PhasePill({ phase }: PhasePillProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-md border border-border border-l-2 border-b-2 px-2 py-0.5 text-[11px] font-semibold leading-[14px]',
        PHASE_BG[phase],
        PHASE_TEXT[phase],
        PHASE_BORDER_LEFT[phase],
        PHASE_BORDER_BOTTOM[phase],
      )}
    >
      {LIFECYCLE_PHASE_LABEL[phase]}
    </span>
  );
}

interface SubsidiaryMobileCardProps {
  sub: BeaconSubsidiary;
}

function SubsidiaryMobileCard({ sub }: SubsidiaryMobileCardProps) {
  const intervention = sub.agents.filter(
    (a) => a.status === 'intervention-required',
  ).length;
  const findingHot = sub.findings.length > 5;
  return (
    <Link
      to={portfolioPaths.subsidiary(sub.slug)}
      className={cn(
        'block rounded-md border border-border border-l-2 bg-surface p-4 transition-colors duration-100 hover:border-border-strong',
        INDUSTRY_BORDER_LEFT[sub.industry],
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            {INDUSTRY_LABEL[sub.industry]}
          </div>
          <div className="mt-0.5 truncate text-sm font-semibold text-foreground">
            {sub.name}
          </div>
        </div>
        <PhasePill phase={sub.lifecyclePhase} />
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-[13px]">
        <div className="flex items-center justify-between">
          <dt className="text-foreground-muted">Capabilities</dt>
          <dd
            className="font-mono text-foreground"
            style={{ fontFeatureSettings: '"tnum"' }}
          >
            {sub.capabilities.length}
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-foreground-muted">Processes</dt>
          <dd
            className="font-mono text-foreground"
            style={{ fontFeatureSettings: '"tnum"' }}
          >
            {sub.processes.length}
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-foreground-muted">Agents</dt>
          <dd
            className="font-mono text-foreground"
            style={{ fontFeatureSettings: '"tnum"' }}
          >
            {sub.agents.length}
            {intervention > 0 && (
              <span className="ml-2 inline-flex items-center rounded-md bg-destructive-surface px-2 py-0.5 text-[11px] font-medium leading-[14px] text-destructive">
                {intervention}
              </span>
            )}
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-foreground-muted">Findings</dt>
          <dd
            className={cn(
              'inline-block rounded-md px-2 py-0.5 font-mono',
              findingHot ? 'bg-warning-surface text-foreground' : 'text-foreground',
            )}
            style={{ fontFeatureSettings: '"tnum"' }}
          >
            {sub.findings.length}
          </dd>
        </div>
        <div className="col-span-2 flex items-center justify-between">
          <dt className="text-foreground-muted">Revenue</dt>
          <dd
            className="font-mono text-foreground"
            style={{ fontFeatureSettings: '"tnum"' }}
          >
            {formatRevenueMillions(sub.revenueMillions)}
          </dd>
        </div>
      </dl>
    </Link>
  );
}
