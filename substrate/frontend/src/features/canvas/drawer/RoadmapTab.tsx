import { cn } from '@/lib/utils';
import type { Initiative, TransformationPlan } from '@/shared/types/api';

// Wave colors are categorical legacy-palette data colors (match WaveScrubber
// bands) — kept; chrome migrated to the token system.
const WAVE_STYLE: Record<string, { band: string; chip: string }> = {
  'Wave 1': { band: 'from-violet-500 to-violet-700', chip: 'bg-violet-500/20 text-violet-200' },
  'Wave 2': { band: 'from-cyan-500 to-cyan-700', chip: 'bg-cyan-500/20 text-cyan-200' },
  'Wave 3': { band: 'from-emerald-500 to-emerald-700', chip: 'bg-emerald-500/20 text-emerald-200' },
};

export function RoadmapTab({
  plan,
  month,
  onScrub,
}: {
  plan: TransformationPlan | undefined;
  month: number;
  onScrub: (m: number) => void;
}) {
  if (!plan) {
    return (
      <div className="p-4 text-sm text-foreground-muted">Loading transformation plan…</div>
    );
  }

  const cvt = plan.currentVsTarget;
  const waves = plan.waveRoadmap.waves;

  const initiativesByWave: Record<string, Initiative[]> = {};
  for (const init of plan.initiatives) {
    (initiativesByWave[init.wave] ??= []).push(init);
  }

  return (
    <div className="flex h-full flex-col">
      {/* Headline */}
      <div className="border-b border-border px-4 py-3">
        <div className="grid grid-cols-3 gap-2 text-center">
          <Metric label="Systems" before={cvt.systemsBefore} after={cvt.systemsAfter} />
          <Metric label="Roles" before={cvt.rolesBefore} after={cvt.rolesAfter} />
          <Metric
            label="Op cost"
            before={cvt.annualOperatingCostBefore}
            after={cvt.annualOperatingCostAfter}
          />
        </div>
      </div>

      {/* Waves */}
      <div className="flex-1 overflow-y-auto px-3 py-3">
        <div className="flex flex-col gap-4">
          {waves.map((wave) => {
            const style = WAVE_STYLE[wave.name] ?? WAVE_STYLE['Wave 1'];
            const inits = initiativesByWave[wave.name] ?? [];
            const isActive = month >= wave.startMonth && month <= wave.endMonth;
            return (
              <div
                key={wave.name}
                className={cn(
                  'overflow-hidden rounded-xl border transition-all',
                  isActive
                    ? 'border-border-strong bg-foreground/[0.06] shadow-[0_0_18px_-6px_hsl(var(--foreground)/0.3)]'
                    : 'border-border/50 bg-foreground/[0.02]'
                )}
              >
                <button
                  onClick={() => onScrub(wave.startMonth)}
                  className="flex w-full items-center gap-3 px-3 py-2.5 text-left"
                >
                  <div className={cn('h-10 w-1.5 rounded-full bg-gradient-to-b', style.band)} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-[13px] font-bold text-foreground">{wave.name}</span>
                      <span className={cn('rounded-full px-1.5 py-0.5 text-[10px] font-semibold', style.chip)}>
                        M{wave.startMonth}–{wave.endMonth}
                      </span>
                    </div>
                    <p className="mt-0.5 text-[11px] text-foreground-muted">
                      {inits.length} initiative{inits.length === 1 ? '' : 's'}
                    </p>
                  </div>
                  <span className="text-[11px] text-primary">Jump →</span>
                </button>
                <div className="divide-y divide-border/50 border-t border-border/50">
                  {inits.map((init) => (
                    <div key={init.id} className="px-3 py-2">
                      <p className="text-[12px] font-semibold text-foreground/90">{init.name}</p>
                      <p className="mt-0.5 text-[11px] leading-snug text-foreground-muted line-clamp-2">
                        {init.description}
                      </p>
                      <div className="mt-1.5 flex flex-wrap gap-1 text-[10px]">
                        <span className="rounded-full bg-foreground/5 px-1.5 py-0.5 text-foreground-muted">
                          {init.complexity}
                        </span>
                        <span className="rounded-full bg-foreground/5 px-1.5 py-0.5 text-foreground-muted">
                          {init.estimatedMonths}mo
                        </span>
                        <span className="rounded-full bg-foreground/5 px-1.5 py-0.5 text-foreground-muted">
                          {init.owner}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function Metric({
  label,
  before,
  after,
}: {
  label: string;
  before: number | string;
  after: number | string;
}) {
  return (
    <div>
      <p className="text-[9px] uppercase tracking-widest text-foreground-subtle">{label}</p>
      <div className="mt-1 flex items-baseline justify-center gap-1">
        <span className="text-sm font-bold text-foreground-subtle line-through decoration-2">{before}</span>
        <span className="text-foreground-subtle">→</span>
        <span className="text-lg font-black text-emerald-300">{after}</span>
      </div>
    </div>
  );
}
