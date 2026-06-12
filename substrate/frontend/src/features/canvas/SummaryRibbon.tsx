import { motion, AnimatePresence } from 'framer-motion';
import type { Lens } from './LensRail';

export interface RibbonStats {
  systems: number;
  processes: number;
  findings: number;
  highFindings: number;
  targetSystems: number;
  /**
   * Display string for the annual-savings stat. Derived from the reduction
   * thesis (GET /intelligence/thesis) by LivingCanvas — homepoc hardcoded
   * '$1.1M' here (fixture seam S14, severed during the port).
   */
  annualSavings: string;
}

// Target/Waves provenance honesty (S14 residual): retirement waves come from
// the TODO(S14) demo heuristics (retirementHeuristics.ts), NOT from plan
// data — the subtitle says so wherever those lenses render over real
// workspace systems (a live engagement must never read fabricated waves as
// plan facts).
const LENS_COPY: Record<Lens, { subtitle: string }> = {
  today: { subtitle: 'Click a node to inspect. Red dot = open risks.' },
  target: {
    subtitle:
      'Faded systems retire. Live ones absorb their work. (Illustrative retirement heuristics — plan engine pending.)',
  },
  waves: {
    subtitle:
      'Drag the timeline — watch the map evolve month by month. (Illustrative retirement heuristics — plan engine pending.)',
  },
};

export function SummaryRibbon({
  lens,
  month,
  stats,
}: {
  lens: Lens;
  month: number;
  stats: RibbonStats;
}) {
  const sub = LENS_COPY[lens].subtitle;

  return (
    <div className="pointer-events-none absolute left-1/2 top-5 z-10 flex w-[min(820px,calc(100vw-14rem))] -translate-x-1/2 flex-col items-center">
      <div
        data-testid="canvas-summary-ribbon"
        className="pointer-events-auto rounded-2xl border border-border bg-surface-raised/80 px-5 py-2.5 shadow-2xl backdrop-blur-xl"
      >
        <div className="flex items-center gap-5">
          <Stat
            stat="systems"
            value={lens === 'target' ? stats.targetSystems : stats.systems}
            label="systems"
            hint={
              lens === 'target'
                ? `from ${stats.systems}`
                : lens === 'waves'
                ? `M${month}`
                : undefined
            }
            accent={lens === 'target' ? 'text-emerald-300' : 'text-foreground'}
          />
          <Divider />
          <Stat stat="manual-procs" value={stats.processes} label="manual procs" />
          <Divider />
          <Stat
            stat="open-risks"
            value={stats.findings}
            label="open risks"
            hint={`${stats.highFindings} high`}
            accent="text-orange-300"
          />
          <Divider />
          <Stat
            stat="annual-savings"
            value={stats.annualSavings}
            label="annual save target"
            // Derived from ReductionService's MARKED-FOR-REPLACEMENT POC
            // dollar constants — an estimate, never a computed plan figure.
            hint="heuristic estimate"
            accent="text-emerald-300"
          />
        </div>
      </div>

      <AnimatePresence mode="wait">
        <motion.p
          key={lens}
          initial={{ opacity: 0, y: -4 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -4 }}
          transition={{ duration: 0.25 }}
          className="mt-2 text-[12px] font-medium text-foreground-muted"
        >
          {sub}
        </motion.p>
      </AnimatePresence>
    </div>
  );
}

function Divider() {
  return <span className="h-6 w-px bg-border" />;
}

function Stat({
  stat,
  value,
  label,
  hint,
  accent = 'text-foreground',
}: {
  /** Machine-readable stat key (DOC-1 §4 item 2) — labels stay human-only. */
  stat: string;
  value: number | string;
  label: string;
  hint?: string;
  accent?: string;
}) {
  return (
    <div data-stat={stat} className="flex items-baseline gap-2">
      <span className={`text-2xl font-black leading-none ${accent}`}>{value}</span>
      <div className="flex flex-col leading-tight">
        <span className="text-[10px] font-bold uppercase tracking-widest text-foreground-subtle">
          {label}
        </span>
        {hint && <span className="text-[10px] text-foreground-subtle">{hint}</span>}
      </div>
    </div>
  );
}
