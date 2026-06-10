import { cn } from '@/lib/utils';
import type { AssistantStatus, AssistantPhase } from '@/shared/types/api';
import type { DrawerTab } from './DetailDrawer';

const STATUS_STYLE: Record<
  AssistantPhase['status'],
  { bar: string; badge: string; label: string }
> = {
  PENDING: { bar: 'bg-foreground/10', badge: 'bg-foreground/5 text-foreground-subtle', label: '○ Pending' },
  IN_PROGRESS: {
    bar: 'bg-sky-500/50',
    badge: 'bg-sky-500/15 text-sky-200',
    label: '⟳ In progress',
  },
  BLOCKED: {
    bar: 'bg-red-500/60',
    badge: 'bg-red-500/20 text-red-200 ring-1 ring-red-500/40',
    label: '⚠ Blocked',
  },
  DONE: {
    bar: 'bg-emerald-500/60',
    badge: 'bg-emerald-500/15 text-emerald-200',
    label: '✓ Done',
  },
};

export function AssistantTab({
  status,
  loading,
  onGoToTab,
}: {
  status: AssistantStatus | undefined;
  loading: boolean;
  onGoToTab: (tab: DrawerTab) => void;
}) {
  if (loading || !status) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-sm text-foreground-muted">Evaluating…</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-border px-4 py-3">
        <p className="text-[10px] font-bold uppercase tracking-[0.3em] text-primary">
          {status.projectName}
        </p>
        <div className="mt-2 flex items-center gap-3">
          <div className="relative h-10 w-10 shrink-0">
            <svg viewBox="0 0 40 40" className="h-full w-full -rotate-90">
              <circle
                cx="20"
                cy="20"
                r="16"
                fill="none"
                stroke="hsl(var(--foreground) / 0.1)"
                strokeWidth="4"
              />
              <circle
                cx="20"
                cy="20"
                r="16"
                fill="none"
                stroke={status.readyForReduction ? 'hsl(var(--success))' : 'hsl(var(--info))'}
                strokeWidth="4"
                strokeDasharray={`${(status.completenessPercent / 100) * 100.5} 100.5`}
                strokeLinecap="round"
                className="transition-all duration-700"
              />
            </svg>
            <span className="absolute inset-0 flex items-center justify-center text-[10px] font-black text-foreground">
              {status.completenessPercent}
            </span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[13px] font-bold text-foreground">
              {status.readyForReduction
                ? 'Ready to reduce'
                : `${100 - status.completenessPercent}% more to go`}
            </p>
            <p className="text-[11px] text-foreground-muted">
              {status.blockers.length} blocker{status.blockers.length === 1 ? '' : 's'}
            </p>
          </div>
        </div>
      </div>

      {/* Next action */}
      <div className="border-b border-border bg-primary/[0.06] px-4 py-3">
        <p className="text-[10px] font-bold uppercase tracking-widest text-primary">
          Next best action
        </p>
        <p className="mt-1 text-[13px] font-semibold leading-snug text-foreground">
          {status.nextAction}
        </p>
      </div>

      {/* Body */}
      <div className="flex-1 overflow-y-auto px-3 py-3">
        {/* Blockers */}
        {status.blockers.length > 0 && (
          <div className="mb-4">
            <p className="mb-2 text-[10px] font-bold uppercase tracking-widest text-red-300">
              Blockers
            </p>
            <div className="flex flex-col gap-1.5">
              {status.blockers.map((b) => (
                <div
                  key={b.id}
                  className="rounded-lg border border-red-500/30 bg-red-500/[0.06] px-3 py-2.5"
                >
                  <p className="text-[12px] font-semibold text-foreground">{b.title}</p>
                  <p className="mt-1 text-[11px] leading-snug text-foreground-muted">{b.description}</p>
                  {b.actionTab && (
                    <button
                      onClick={() => onGoToTab(b.actionTab as DrawerTab)}
                      className="mt-2 rounded-md bg-red-500/20 px-2 py-1 text-[11px] font-semibold text-red-200 hover:bg-red-500/30"
                    >
                      {b.actionLabel} →
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Phases */}
        <p className="mb-2 text-[10px] font-bold uppercase tracking-widest text-foreground-subtle">
          Playbook
        </p>
        <div className="flex flex-col gap-2">
          {status.phases.map((phase, i) => {
            const style = STATUS_STYLE[phase.status];
            return (
              <div
                key={phase.name}
                className="overflow-hidden rounded-lg border border-border/50 bg-foreground/[0.02]"
              >
                <div className="flex items-center gap-2 px-3 py-2">
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-foreground/10 text-[10px] font-black text-foreground-muted">
                    {i + 1}
                  </span>
                  <div className="flex-1 min-w-0">
                    <p className="text-[12px] font-bold text-foreground">{phase.name}</p>
                    <p className="text-[10px] text-foreground-muted">{phase.subtitle}</p>
                  </div>
                  <span
                    className={cn(
                      'shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold',
                      style.badge
                    )}
                  >
                    {style.label}
                  </span>
                </div>
                <div className={cn('h-0.5', style.bar)} />
                <ul className="divide-y divide-border/50 bg-foreground/[0.02]">
                  {phase.checks.map((c, j) => (
                    <li key={j} className="flex items-start gap-2 px-3 py-1.5">
                      <span
                        className={cn(
                          'mt-[3px] inline-block h-2.5 w-2.5 shrink-0 rounded-full',
                          c.passed ? 'bg-emerald-400' : 'bg-foreground/20 ring-1 ring-border-strong'
                        )}
                      />
                      <div className="flex-1 min-w-0">
                        <p
                          className={cn(
                            'text-[11px]',
                            c.passed ? 'text-foreground-muted line-through' : 'text-foreground/80'
                          )}
                        >
                          {c.label}
                        </p>
                        {!c.passed && c.gapDescription && (
                          <p className="mt-0.5 text-[10px] italic text-foreground-muted">
                            {c.gapDescription}
                          </p>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
