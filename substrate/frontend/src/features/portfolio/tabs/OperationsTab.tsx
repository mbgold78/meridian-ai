import { useMemo, useState } from 'react';
import { CaretDown, CaretRight, Robot } from '@phosphor-icons/react';
import type {
  AgentStatus,
  BeaconAgent,
  BeaconFinding,
  BeaconSubsidiary,
  Severity,
} from '@/fixtures/beacon';
import { cn } from '@/lib/utils';
import { todayIso } from '../clock';

/**
 * Operations tab — what the operating company is doing today.
 *
 * Three regions, top to bottom:
 *   1. Fleet stat strip — agents active, agents in intervention, open
 *      findings. Counts are computed from the fixture so they cannot
 *      drift from the rest of the demo.
 *   2. Recent agent activity — vertical row list with status pills.
 *      The fixture's `recentActivity` text is rendered verbatim; the
 *      "12 minutes ago" timestamp the spec proposes is a fixture
 *      extension candidate, documented in the completion report.
 *   3. Open findings — 2-column grid of cards with severity pills and
 *      related-system chips when the finding is process-bound.
 *
 * Port note (C9): "surfaced N days ago" reads the injectable clock in
 * `../clock` (fixture-pinned by default) instead of importing the
 * DEMO_CURRENT_DATE constant directly — port-map §5 S15.
 */
export function OperationsTab({ sub }: { sub: BeaconSubsidiary }) {
  const stats = useMemo(() => {
    const intervention = sub.agents.filter((a) => a.status === 'intervention-required').length;
    return {
      active: sub.agents.length,
      intervention,
      findings: sub.findings.length,
    };
  }, [sub]);

  // Inline expansion state for the agent fleet — clicking an agent row
  // toggles a panel that shows the agent's deployment criteria + the
  // full oversight chain. Per governance-3 spec Item 7 (Option a).
  const [expandedAgentId, setExpandedAgentId] = useState<string | null>(null);

  return (
    <section aria-label="Operations" className="flex flex-col gap-12">
      <div>
        <header className="mb-6">
          <h2 className="text-[22px] font-semibold leading-[28px] -tracking-[0.005em] text-foreground">
            Operating today
          </h2>
          <p className="mt-1 text-[14px] leading-[20px] text-foreground-muted">
            Agent fleet status, current findings, process health.
          </p>
        </header>
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
          <FleetStatCard label="Agents active" value={stats.active} />
          <FleetStatCard
            label="Agents in intervention"
            value={stats.intervention}
            tone={stats.intervention > 0 ? 'destructive' : 'neutral'}
          />
          <FleetStatCard
            label="Open findings"
            value={stats.findings}
            tone={stats.findings > 0 ? 'warning' : 'neutral'}
          />
        </div>
      </div>

      <div>
        <header className="mb-4">
          <h3 className="text-[18px] font-semibold leading-[24px] text-foreground">
            Recent agent activity
          </h3>
        </header>
        {sub.agents.length === 0 ? (
          <p className="rounded-md border border-border bg-surface p-6 text-[14px] text-foreground-muted">
            No agents deployed for this subsidiary yet.
          </p>
        ) : (
          <ul className="overflow-hidden rounded-md border border-border bg-surface">
            {sub.agents.map((agent, i) => {
              const isExpanded = expandedAgentId === agent.id;
              return (
                <li
                  key={agent.id}
                  className={cn(
                    i !== sub.agents.length - 1 && 'border-b border-border',
                  )}
                >
                  <AgentActivityRow
                    agent={agent}
                    isExpanded={isExpanded}
                    onToggle={() =>
                      setExpandedAgentId(isExpanded ? null : agent.id)
                    }
                  />
                  {isExpanded && <AgentDeploymentPanel agent={agent} />}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div>
        <header className="mb-4">
          <h3 className="text-[18px] font-semibold leading-[24px] text-foreground">
            Open findings
          </h3>
        </header>
        {sub.findings.length === 0 ? (
          <p className="rounded-md border border-border bg-surface p-6 text-[14px] text-foreground-muted">
            No open findings for this subsidiary.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
            {sub.findings.map((finding) => (
              <FindingCard key={finding.id} finding={finding} sub={sub} />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  Fleet stat card                                                    */
/* ------------------------------------------------------------------ */

interface FleetStatCardProps {
  label: string;
  value: number;
  tone?: 'neutral' | 'warning' | 'destructive';
}

function FleetStatCard({ label, value, tone = 'neutral' }: FleetStatCardProps) {
  return (
    <article className="rounded-md border border-border bg-surface p-5">
      <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
        {label}
      </p>
      <p
        className="mt-3 text-[28px] font-semibold leading-[34px] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {value}
      </p>
      {tone === 'destructive' && (
        <span className="mt-2 inline-flex items-center rounded-md border border-transparent bg-destructive-surface px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em] text-destructive">
          {value} require intervention
        </span>
      )}
      {tone === 'warning' && (
        <span className="mt-2 inline-flex items-center rounded-md border border-transparent bg-warning-surface px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em] text-warning">
          {value} elevated
        </span>
      )}
    </article>
  );
}

/* ------------------------------------------------------------------ */
/*  Agent activity row                                                 */
/* ------------------------------------------------------------------ */

interface AgentActivityRowProps {
  agent: BeaconAgent;
  isExpanded: boolean;
  onToggle: () => void;
}

function AgentActivityRow({ agent, isExpanded, onToggle }: AgentActivityRowProps) {
  const provenance = formatAgentProvenance(agent);
  const hasDeployment = Boolean(agent.deploymentCriteria);
  const Caret = isExpanded ? CaretDown : CaretRight;
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={isExpanded}
      aria-label={`${isExpanded ? 'Collapse' : 'Expand'} ${agent.name} deployment detail`}
      className="flex w-full items-start gap-3 p-4 text-left transition-colors duration-100 hover:bg-surface-raised"
    >
      <span
        className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-surface-raised text-foreground-muted"
        aria-hidden
      >
        <Robot size={18} weight="regular" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-baseline justify-between gap-3">
          <p className="text-[14px] font-medium leading-[20px] text-foreground">
            {agent.name}
          </p>
          <AgentStatusPill status={agent.status} />
        </div>
        <p className="mt-1 text-[13px] leading-[18px] text-foreground-muted">
          {agent.role}
        </p>
        <p className="mt-1 text-[13px] leading-[18px] text-foreground">
          {agent.recentActivity}
        </p>
        {provenance && (
          <p className="mt-1 text-[12px] leading-[16px] text-foreground-muted">
            {provenance}
          </p>
        )}
      </div>
      {hasDeployment && (
        <span
          aria-hidden
          className="mt-1 inline-flex shrink-0 text-foreground-muted"
        >
          <Caret size={16} weight="regular" />
        </span>
      )}
    </button>
  );
}

/**
 * Inline expansion panel — surfaces the agent's `deploymentCriteria`
 * and the full oversight chain in a structured form. Reserved field
 * from governance-1 (queue item #16); rendered for the first time
 * here per governance-3 Item 7 Option a.
 */
function AgentDeploymentPanel({ agent }: { agent: BeaconAgent }) {
  return (
    <div
      role="region"
      aria-label={`${agent.name} deployment detail`}
      className="border-t border-border bg-surface-raised px-4 py-5"
    >
      <div className="flex flex-col gap-5 md:flex-row md:gap-8">
        <div className="md:basis-3/5">
          <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Deployment criteria
          </p>
          {agent.deploymentCriteria ? (
            <p className="mt-2 text-[14px] leading-[20px] text-foreground">
              {agent.deploymentCriteria}
            </p>
          ) : (
            <p className="mt-2 text-[14px] leading-[20px] italic text-foreground-muted">
              Deployment criteria not yet recorded for this agent.
            </p>
          )}
        </div>
        <div className="md:basis-2/5">
          <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Oversight chain
          </p>
          {agent.oversightChain && agent.oversightChain.length > 0 ? (
            <ol role="list" className="mt-2 flex flex-col gap-1.5">
              {agent.oversightChain.map((owner, idx) => (
                <li
                  key={`${owner}-${idx}`}
                  className="flex items-baseline gap-2 text-[13px] leading-[18px] text-foreground"
                >
                  <span
                    aria-hidden
                    className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md border border-border bg-surface font-mono text-[11px] font-semibold text-foreground-muted"
                    style={{ fontFeatureSettings: '"tnum"' }}
                  >
                    {idx + 1}
                  </span>
                  <span>{owner}</span>
                </li>
              ))}
            </ol>
          ) : (
            <p className="mt-2 text-[13px] leading-[18px] italic text-foreground-muted">
              No oversight chain recorded.
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Builds the compact provenance line for an agent row. Returns null if
 * any of the four provenance fields are missing — the row simply omits
 * the line in that case (e.g. agents at subsidiaries that haven't been
 * polished for substrate-as-governance surfacing yet).
 *
 * The deployment criteria field is intentionally NOT included here; it's
 * reserved for the click-through detail surface (governance-3).
 */
function formatAgentProvenance(agent: BeaconAgent): string | null {
  const { deployedBy, deployedAt, oversightChain } = agent;
  if (!deployedBy || !deployedAt || !oversightChain || oversightChain.length === 0) {
    return null;
  }
  const date = formatDeploymentDate(deployedAt);
  const chain = oversightChain.join(' → ');
  return `Deployed by ${deployedBy} · ${date} · Oversight: ${chain}`;
}

const SHORT_MONTH = [
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

/**
 * Format an ISO date string (YYYY-MM-DD) as `Mon DD YYYY`. Falls back to
 * the raw input if parsing fails so demo content never renders blank.
 */
function formatDeploymentDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) return iso;
  const year = match[1];
  const monthIdx = Number(match[2]) - 1;
  const day = Number(match[3]);
  if (monthIdx < 0 || monthIdx > 11) return iso;
  return `${SHORT_MONTH[monthIdx]} ${day} ${year}`;
}

interface AgentStatusPillProps {
  status: AgentStatus;
}

const AGENT_STATUS_PILL: Record<
  AgentStatus,
  { label: string; cls: string }
> = {
  live: {
    label: 'Operating',
    cls: 'bg-success-surface text-success',
  },
  shadow: {
    label: 'Shadow mode',
    cls: 'bg-info-surface text-info',
  },
  paused: {
    label: 'Paused',
    cls: 'bg-warning-surface text-warning',
  },
  'intervention-required': {
    label: 'In intervention',
    cls: 'bg-destructive-surface text-destructive',
  },
};

function AgentStatusPill({ status }: AgentStatusPillProps) {
  const { label, cls } = AGENT_STATUS_PILL[status];
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center rounded-md border border-transparent px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em]',
        cls,
      )}
    >
      {label}
    </span>
  );
}

/* ------------------------------------------------------------------ */
/*  Finding card                                                       */
/* ------------------------------------------------------------------ */

interface FindingCardProps {
  finding: BeaconFinding;
  sub: BeaconSubsidiary;
}

function FindingCard({ finding, sub }: FindingCardProps) {
  const surfacedDays = daysBetween(finding.surfacedAt, todayIso());
  const relatedSystems = useMemo(() => {
    if (!finding.processId) return [];
    const proc = sub.processes.find((p) => p.id === finding.processId);
    if (!proc) return [];
    return proc.systemIds
      .map((id) => sub.systems.find((s) => s.id === id))
      .filter((s): s is NonNullable<typeof s> => Boolean(s));
  }, [finding, sub]);

  return (
    <article className="flex flex-col rounded-md border border-border bg-surface-raised p-5">
      <header className="flex items-start justify-between gap-3">
        <h4 className="text-[18px] font-semibold leading-[24px] text-foreground">
          {finding.title}
        </h4>
        <SeverityPill severity={finding.severity} />
      </header>
      <p className="mt-2 text-[14px] leading-[20px] text-foreground-muted">
        {finding.detail}
      </p>
      {relatedSystems.length > 0 && (
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Related systems
          </span>
          {relatedSystems.map((sys) => (
            <span
              key={sys.id}
              className="inline-flex items-center rounded-md border border-border bg-surface px-2 py-0.5 text-[11px] font-medium text-foreground"
            >
              {sys.name}
            </span>
          ))}
        </div>
      )}
      <p
        className="mt-auto pt-4 text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        Surfaced {surfacedDays} day{surfacedDays === 1 ? '' : 's'} ago
      </p>
    </article>
  );
}

const SEVERITY_PILL: Record<Severity, { label: string; cls: string }> = {
  low: { label: 'Low', cls: 'bg-info-surface text-info' },
  medium: { label: 'Medium', cls: 'bg-warning-surface text-warning' },
  high: { label: 'High', cls: 'bg-destructive-surface text-destructive' },
};

function SeverityPill({ severity }: { severity: Severity }) {
  const { label, cls } = SEVERITY_PILL[severity];
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center rounded-md border border-transparent px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em]',
        cls,
      )}
    >
      {label}
    </span>
  );
}

/**
 * Whole-day delta between two ISO date strings (`from` → `to`),
 * computed in UTC so the result is stable across reader time zones.
 * Returns a non-negative integer; if `from` is after `to` (future
 * date), returns 0 so the rendered "N days ago" label never goes
 * negative for the demo.
 */
function daysBetween(fromIso: string, toIso: string): number {
  const from = parseIsoDate(fromIso);
  const to = parseIsoDate(toIso);
  if (from === null || to === null) return 0;
  const ms = to - from;
  if (ms <= 0) return 0;
  return Math.floor(ms / 86_400_000);
}

function parseIsoDate(iso: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!m) return null;
  const year = Number(m[1]);
  const monthIdx = Number(m[2]) - 1;
  const day = Number(m[3]);
  if (monthIdx < 0 || monthIdx > 11) return null;
  return Date.UTC(year, monthIdx, day);
}
