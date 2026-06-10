import { Link, useParams } from 'react-router-dom';
import { CaretLeft, GitBranch } from '@phosphor-icons/react';
import { Button } from '@/components/ui/button';
import { getCascade } from '@/fixtures/beacon';
import type { BeaconCascade, CascadeLevel } from '@/fixtures/beacon';
import { cn } from '@/lib/utils';
import { portfolioPaths } from './paths';

/**
 * Cascade detail surface — the substrate's most distinctive demo beat
 * (ported from homepoc under the product route namespace, chunk C9).
 *
 * The Operating Partner clicks "Inspect cascade" on the dashboard and
 * lands here. They see the original critique they issued (durable
 * through the chain), every downstream level's translation, the current
 * resting state at the bottom of the chain, and an affordance to drill
 * to that level. The "Drill to L[N]" button is a read-only signal that
 * the substrate makes single-throat-to-choke possible — it doesn't
 * actually navigate anywhere in v1.
 *
 * Routing: `/portfolio/cascade/:cascadeId`. Unknown ids render a
 * fallback panel with a back link rather than crashing.
 */
export function CascadeDetailView() {
  const { cascadeId = '' } = useParams();
  const cascade = getCascade(cascadeId);

  if (!cascade) return <CascadeNotFound id={cascadeId} />;

  return (
    <div className="mx-auto max-w-[1440px]">
      <CascadeBackLink />
      <CascadeHeader cascade={cascade} />
      <div className="px-8 pb-12 pt-8">
        <LevelTimeline levels={cascade.levels} />
        <CascadeFooter cascade={cascade} />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Back link                                                          */
/* ------------------------------------------------------------------ */

function CascadeBackLink() {
  return (
    <div className="px-8 pb-2 pt-6">
      <Link
        to={portfolioPaths.dashboard}
        className="inline-flex items-center gap-1 text-[13px] font-medium leading-[18px] text-foreground-muted transition-colors duration-100 hover:text-foreground"
      >
        <CaretLeft size={14} weight="regular" aria-hidden />
        Back to portfolio
      </Link>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Header strip — eyebrow, original critique, acceptance criteria     */
/* ------------------------------------------------------------------ */

function CascadeHeader({ cascade }: { cascade: BeaconCascade }) {
  return (
    <header className="border-b border-border px-8 py-8">
      <div className="flex flex-col gap-8 md:flex-row md:items-start md:justify-between">
        <div className="md:basis-3/5">
          <div className="flex items-center gap-1.5">
            <GitBranch
              size={16}
              weight="regular"
              aria-hidden
              className="text-primary"
            />
            <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-primary">
              In-flight cascade
            </p>
          </div>
          <h1 className="mt-3 font-serif text-[36px] font-semibold leading-[40px] -tracking-[0.01em] text-foreground">
            {cascade.originalCritique}
          </h1>
          <p className="mt-3 text-[13px] leading-[18px] text-foreground-muted">
            Originated by {cascade.originatedBy} on{' '}
            {formatCascadeDate(cascade.originatedAt)} · {cascade.levels.length} level chain
          </p>
        </div>
        <aside className="rounded-md border border-border bg-surface p-5 md:basis-2/5">
          <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Acceptance criteria
          </p>
          <p className="mt-2 text-[14px] leading-[20px] text-foreground">
            {cascade.acceptanceCriteria}
          </p>
        </aside>
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------ */
/*  Level timeline                                                     */
/* ------------------------------------------------------------------ */

interface LevelTimelineProps {
  levels: CascadeLevel[];
}

function LevelTimeline({ levels }: LevelTimelineProps) {
  return (
    <section aria-label="Cascade level timeline" className="flex flex-col">
      <header className="mb-4">
        <h2 className="text-[18px] font-semibold leading-[24px] text-foreground">
          Level-by-level transparency
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          Every level's decision, surfaced in one place.
        </p>
      </header>
      <ol role="list" className="flex flex-col">
        {levels.map((lvl, i) => (
          <LevelRow
            key={lvl.level}
            level={lvl}
            isLast={i === levels.length - 1}
          />
        ))}
      </ol>
    </section>
  );
}

interface LevelRowProps {
  level: CascadeLevel;
  isLast: boolean;
}

function LevelRow({ level, isLast }: LevelRowProps) {
  const pill = LEVEL_STATE_PILL[level.state];
  return (
    <li className="relative flex gap-4">
      <div className="flex flex-col items-center" aria-hidden>
        <span
          className="mt-1 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-border bg-surface-raised font-mono text-[12px] font-semibold leading-[16px] text-foreground"
          style={{ fontFeatureSettings: '"tnum"' }}
        >
          L{level.level}
        </span>
        {!isLast && (
          <span className="mt-1 w-px flex-1 bg-border" />
        )}
      </div>
      <article
        className={cn(
          'flex flex-1 flex-col rounded-md border border-border bg-surface-raised p-5',
          isLast ? 'mb-0' : 'mb-6',
        )}
      >
        <header className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
              L{level.level} · {level.ownerLabel}
            </p>
            <p className="mt-1 text-[13px] leading-[18px] text-foreground-muted">
              {formatCascadeDate(level.timestamp)}
            </p>
          </div>
          <span
            className={cn(
              'inline-flex shrink-0 items-center rounded-md border border-transparent px-2 py-0.5 text-[11px] font-medium leading-[14px] uppercase tracking-[0.05em]',
              pill.cls,
            )}
          >
            {pill.label}
          </span>
        </header>
        <LevelContent level={level} />
      </article>
    </li>
  );
}

function LevelContent({ level }: { level: CascadeLevel }) {
  if (level.state === 'cascaded' && level.refinedCritique) {
    return (
      <div className="mt-3">
        <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
          Refined critique passed down
        </p>
        <p className="mt-1 text-[14px] leading-[20px] text-foreground">
          {level.refinedCritique}
        </p>
      </div>
    );
  }
  if (level.state === 'declined' && level.declineReasoning) {
    return (
      <div className="mt-3">
        <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
          Decline reasoning passed up
        </p>
        <p className="mt-1 text-[14px] leading-[20px] text-foreground">
          {level.declineReasoning}
        </p>
      </div>
    );
  }
  if (level.state === 'absorbed') {
    return (
      <p className="mt-3 text-[14px] leading-[20px] text-foreground">
        This level absorbed the work — no further cascade needed.
      </p>
    );
  }
  return (
    <p className="mt-3 text-[14px] leading-[20px] text-foreground-muted">
      This level is currently looking at the cascade — no decision yet.
    </p>
  );
}

const LEVEL_STATE_PILL: Record<
  CascadeLevel['state'],
  { label: string; cls: string }
> = {
  looking: {
    label: 'Looking',
    cls: 'bg-warning-surface text-warning',
  },
  absorbed: {
    label: 'Absorbed',
    cls: 'bg-success-surface text-success',
  },
  cascaded: {
    label: 'Cascaded',
    cls: 'bg-info-surface text-info',
  },
  declined: {
    label: 'Declined',
    cls: 'bg-destructive-surface text-destructive',
  },
};

/* ------------------------------------------------------------------ */
/*  Footer panel                                                       */
/* ------------------------------------------------------------------ */

function CascadeFooter({ cascade }: { cascade: BeaconCascade }) {
  const bottomLevel = cascade.levels[cascade.levels.length - 1];
  const summary = footerSummary(cascade, bottomLevel);
  return (
    <footer className="mt-10 rounded-md border border-border bg-surface p-6">
      <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
        Cascade summary
      </p>
      <p className="mt-2 text-[14px] leading-[20px] text-foreground">
        {summary}
      </p>
    </footer>
  );
}

function footerSummary(
  cascade: BeaconCascade,
  bottomLevel: CascadeLevel,
): string {
  switch (cascade.currentState) {
    case 'in-flight':
      return `Currently at L${bottomLevel.level}, state: ${bottomLevel.state}.`;
    case 'resolved-looks-good':
      return 'Resolved — looks good.';
    case 'resolved-declined':
      return 'Resolved — declined.';
  }
}

/* ------------------------------------------------------------------ */
/*  Fallback — cascade id not in fixture                               */
/* ------------------------------------------------------------------ */

function CascadeNotFound({ id }: { id: string }) {
  return (
    <div className="mx-auto max-w-screen-md px-8 py-12">
      <div className="rounded-md border border-destructive/40 bg-destructive-surface p-8">
        <h1 className="text-[22px] font-semibold leading-[28px] text-destructive">
          Cascade not found
        </h1>
        <p className="mt-2 text-[14px] leading-[20px] text-foreground-muted">
          No Beacon cascade matches the id "{id}".
        </p>
        <Button asChild variant="outline" size="sm" className="mt-6">
          <Link to={portfolioPaths.dashboard} className="gap-1">
            <CaretLeft size={16} aria-hidden />
            Back to portfolio
          </Link>
        </Button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Date helper                                                        */
/* ------------------------------------------------------------------ */

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

function formatCascadeDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!m) return iso;
  const monthIdx = Number(m[2]) - 1;
  if (monthIdx < 0 || monthIdx > 11) return iso;
  return `${SHORT_MONTH[monthIdx]} ${Number(m[3])} ${m[1]}`;
}
