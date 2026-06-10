import { Compass } from '@phosphor-icons/react';
import {
  LIFECYCLE_PHASE_LABEL,
  LIFECYCLE_PHASE_ORDER,
} from '@/fixtures/beacon';
import type {
  BeaconInitiative,
  BeaconSubsidiary,
  InitiativeStatus,
  LifecyclePhase,
} from '@/fixtures/beacon';
import { cn } from '@/lib/utils';

/**
 * Plan tab — initiatives in flight and lifecycle progression.
 *
 * Initiatives come straight from the fixture. Each initiative carries
 * its own `progressPct` (D2 §4.3 / Block 5 fixture extension); the
 * meter renders that value directly so progress can vary per
 * initiative without being synthesized at the presentation layer.
 *
 * Lifecycle progression renders the seven phase chips with the
 * subsidiary's current phase highlighted in `--primary`. The
 * completion-requirements list is a per-phase canonical set of bullets
 * — what "done" looks like to leave the current phase. This is also a
 * fixture-extension candidate (per-subsidiary completion criteria),
 * but a portfolio-wide default is enough for Stage 4 demo polish.
 */
export function PlanTab({ sub }: { sub: BeaconSubsidiary }) {
  return (
    <section aria-label="Plan" className="flex flex-col gap-12">
      <div>
        <header className="mb-6">
          <h2 className="text-[22px] font-semibold leading-[28px] -tracking-[0.005em] text-foreground">
            Initiatives in flight
          </h2>
          <p className="mt-1 text-[14px] leading-[20px] text-foreground-muted">
            What {sub.name} is actively working on this quarter.
          </p>
        </header>
        {sub.initiatives.length === 0 ? (
          <p className="rounded-md border border-border bg-surface p-6 text-[14px] text-foreground-muted">
            No initiatives recorded for this subsidiary yet.
          </p>
        ) : (
          <ul className="overflow-hidden rounded-md border border-border bg-surface">
            {sub.initiatives.map((init, i) => (
              <li
                key={init.id}
                className={cn(
                  'p-6',
                  i !== sub.initiatives.length - 1 && 'border-b border-border',
                )}
              >
                <InitiativeRow init={init} />
              </li>
            ))}
          </ul>
        )}
      </div>

      <div>
        <header className="mb-4">
          <h3 className="text-[18px] font-semibold leading-[24px] text-foreground">
            Lifecycle progression
          </h3>
          <p className="mt-1 text-[14px] leading-[20px] text-foreground-muted">
            Where {sub.name} sits today and what completing the current phase
            requires.
          </p>
        </header>
        <LifecycleSpine current={sub.lifecyclePhase} />
        <div className="rounded-md border border-border bg-surface p-5">
          <p className="text-[13px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Completing the {LIFECYCLE_PHASE_LABEL[sub.lifecyclePhase]} phase requires
          </p>
          <ul className="mt-3 flex flex-col gap-2">
            {PHASE_COMPLETION_REQUIREMENTS[sub.lifecyclePhase].map((req) => (
              <li
                key={req}
                className="flex gap-2 text-[14px] leading-[20px] text-foreground"
              >
                <span aria-hidden className="text-foreground-muted">
                  —
                </span>
                <span>{req}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  Initiative row                                                     */
/* ------------------------------------------------------------------ */

interface InitiativeRowProps {
  init: BeaconInitiative;
}

function InitiativeRow({ init }: InitiativeRowProps) {
  const progress = init.progressPct;
  const targetLabel = formatTargetDate(init.targetDate);
  const valueLabel =
    init.valueDollars > 0 ? `$${(init.valueDollars / 1_000_000).toFixed(1)}M` : '—';
  const cycleLabel =
    init.valueCycleTimeReductionPct > 0
      ? `${init.valueCycleTimeReductionPct}% faster`
      : '—';
  return (
    <article aria-label={init.title} className="flex items-start gap-4">
      <span
        className="mt-0.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-border bg-surface-raised text-foreground-subtle"
        aria-hidden
      >
        <Compass size={18} weight="regular" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-start justify-between gap-3">
          <h4 className="text-[16px] font-semibold leading-[22px] -tracking-[0.005em] text-foreground">
            {init.title}
          </h4>
          <StatusChip status={init.status} />
        </div>
        <p className="mt-1 text-[13px] leading-[19px] text-foreground-muted">
          {init.summary ?? buildInitiativeBlurb(init)}
        </p>

        {/* Metric strip — quiet, dense, scannable. Value is framed as a
            potential consequence of the plan, never a headline. */}
        <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 border-t border-border/60 pt-4 sm:grid-cols-4">
          <Metric label="Annual value" value={valueLabel} hint="potential" />
          <Metric label="Cycle time" value={cycleLabel} />
          <Metric label="Owner" value={init.owner} avatar />
          <Metric label="Target" value={targetLabel} mono />
        </dl>

        {/* Progress meter — thin, restrained, tabular readout. */}
        <div className="mt-4 flex items-center gap-3">
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-border/60">
            <div
              className="h-full rounded-full bg-foreground/85"
              style={{ width: `${progress}%` }}
              aria-hidden
            />
          </div>
          <span
            className="w-9 text-right text-[12px] font-semibold tabular-nums text-foreground"
            style={{ fontFeatureSettings: '"tnum"' }}
            aria-label={`Progress ${progress}%`}
          >
            {progress}%
          </span>
        </div>
      </div>
    </article>
  );
}

/* ------------------------------------------------------------------ */
/*  Metric + owner avatar                                              */
/* ------------------------------------------------------------------ */

function Metric({
  label,
  value,
  hint,
  mono,
  avatar,
}: {
  label: string;
  value: string;
  hint?: string;
  mono?: boolean;
  avatar?: boolean;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] font-medium uppercase leading-[12px] tracking-[0.06em] text-foreground-subtle">
        {label}
      </dt>
      <dd className="mt-1 flex items-center gap-1.5 text-[13px] leading-[18px] text-foreground">
        {avatar && <OwnerAvatar name={value} />}
        <span
          className={cn('truncate', mono && 'font-mono text-[12px]')}
          style={mono ? { fontFeatureSettings: '"tnum"' } : undefined}
        >
          {value}
        </span>
        {hint && (
          <span className="text-[11px] text-foreground-subtle">{hint}</span>
        )}
      </dd>
    </div>
  );
}

function OwnerAvatar({ name }: { name: string }) {
  const initials = name
    .split(' ')
    .map((p) => p[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <span
      className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-border bg-surface-raised text-[9px] font-semibold leading-none text-foreground-muted"
      aria-hidden
    >
      {initials}
    </span>
  );
}

/* ------------------------------------------------------------------ */
/*  Status chip                                                        */
/* ------------------------------------------------------------------ */

// Inlined here for v1; promotion to a shared `<StatusPill>`/`<StatusChip>`
// component is Block 6 / Stage 7 work per phase 06-block-3 spec.
const INITIATIVE_STATUS_BORDER_LEFT: Record<InitiativeStatus, string> = {
  proposed: 'border-l-foreground-muted',
  'in-flight': 'border-l-info',
  complete: 'border-l-success',
  stalled: 'border-l-warning',
};

function StatusChip({ status }: { status: InitiativeStatus }) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center rounded-md border border-border border-l-2 bg-surface-raised px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em] text-foreground',
        INITIATIVE_STATUS_BORDER_LEFT[status],
      )}
    >
      {INITIATIVE_STATUS_LABEL[status]}
    </span>
  );
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

const INITIATIVE_STATUS_LABEL: Record<InitiativeStatus, string> = {
  proposed: 'Proposed',
  'in-flight': 'In flight',
  complete: 'Complete',
  stalled: 'Stalled',
};

/* ------------------------------------------------------------------ */
/*  Lifecycle spine — the seven-phase progression as a connected        */
/*  stepper. Completed phases carry their identity color; the current   */
/*  phase is the ONE reserved amber accent; upcoming phases are hollow.  */
/* ------------------------------------------------------------------ */

const PHASE_DOT: Record<LifecyclePhase, string> = {
  'plug-in': 'bg-phase-plug-in',
  design:    'bg-phase-design',
  map:       'bg-phase-map',
  plan:      'bg-phase-plan',
  execute:   'bg-phase-execute',
  monitor:   'bg-phase-monitor',
  automate:  'bg-phase-automate',
};

function LifecycleSpine({ current }: { current: LifecyclePhase }) {
  const currentIdx = LIFECYCLE_PHASE_ORDER.indexOf(current);
  return (
    <ol className="mb-7 flex items-start" aria-label="Lifecycle progression">
      {LIFECYCLE_PHASE_ORDER.map((phase, i) => {
        const state = i < currentIdx ? 'done' : i === currentIdx ? 'current' : 'todo';
        const isLast = i === LIFECYCLE_PHASE_ORDER.length - 1;
        return (
          <li key={phase} className="min-w-0 flex-1">
            <div className="flex items-center">
              {state === 'current' ? (
                <span
                  className="relative z-10 flex h-4 w-4 shrink-0 items-center justify-center"
                  aria-hidden
                >
                  <span className="absolute h-4 w-4 rounded-full bg-primary/20" />
                  <span className="relative h-2.5 w-2.5 rounded-full bg-primary ring-2 ring-background" />
                </span>
              ) : state === 'done' ? (
                <span
                  className={cn('relative z-10 h-2.5 w-2.5 shrink-0 rounded-full', PHASE_DOT[phase])}
                  aria-hidden
                />
              ) : (
                <span
                  className="relative z-10 h-2.5 w-2.5 shrink-0 rounded-full border border-border-strong bg-background"
                  aria-hidden
                />
              )}
              {!isLast && (
                <span
                  className={cn('h-px flex-1', state === 'done' ? 'bg-border-strong' : 'bg-border')}
                  aria-hidden
                />
              )}
            </div>
            <div className="mt-3 pr-3">
              <p
                className={cn(
                  'text-[10px] font-medium uppercase leading-[13px] tracking-[0.06em]',
                  state === 'current'
                    ? 'font-semibold text-foreground'
                    : state === 'done'
                      ? 'text-foreground-muted'
                      : 'text-foreground-subtle',
                )}
              >
                {LIFECYCLE_PHASE_LABEL[phase]}
              </p>
              {state === 'current' && (
                <p className="mt-1 text-[10px] font-medium leading-[13px] text-primary">
                  Current phase
                </p>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

function formatTargetDate(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
  } catch {
    return iso;
  }
}

function buildInitiativeBlurb(init: BeaconInitiative): string {
  const value =
    init.valueDollars > 0
      ? `Estimated value $${(init.valueDollars / 1_000_000).toFixed(1)}M annually.`
      : '';
  const cycle =
    init.valueCycleTimeReductionPct > 0
      ? `Targets ${init.valueCycleTimeReductionPct}% cycle-time reduction.`
      : '';
  return [value, cycle].filter(Boolean).join(' ') || 'Active initiative.';
}

/**
 * Per-phase completion requirements. Authored as a portfolio-wide
 * default. A future fixture extension can override per subsidiary if
 * the demo needs subsidiary-specific completion gates.
 */
const PHASE_COMPLETION_REQUIREMENTS: Record<LifecyclePhase, string[]> = {
  'plug-in': [
    'Universe inputs registered across all six reader kinds',
    'Initial capability tree drafted from starter template',
    'COO and operating partners onboarded to the platform',
  ],
  design: [
    'Target capability model rationalized against AI-suggested gaps',
    'Customer nomenclature finalized and locked at workspace level',
    'Decision log populated with structural choices and rationale',
  ],
  map: [
    'Existing systems inventoried and mapped to capabilities',
    'Process catalog cross-referenced against capability tree',
    'Tribal-knowledge concentration scored at the process level',
  ],
  plan: [
    'Initiative portfolio prioritized against capability impact',
    'Cross-portfolio opportunities enrolled in or formally declined',
    'Resource and budget commitments confirmed by Beacon HQ',
    'Quarterly milestone calendar published to Corporate Operating Partner',
  ],
  execute: [
    'Initiatives in flight tracked against committed milestones',
    'First agents deployed in shadow mode against high-volume processes',
    'Process protocol drafts approved by capability owners',
  ],
  monitor: [
    'Capability health dashboards live and watched by COO',
    'SLA breach alerts wired to the Agent Operations Center',
    'Federation rollups returning clean against parent dashboards',
  ],
  automate: [
    'Agents promoted from shadow to live with explicit playbooks',
    'Intervention playbooks owned by named human operators',
    'Continuous administration loop closed across all primary capabilities',
  ],
};
