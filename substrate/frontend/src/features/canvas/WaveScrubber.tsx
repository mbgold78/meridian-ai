import { useEffect, useState } from 'react';

// Wave band colors (violet/cyan/emerald) are categorical legacy-palette data
// colors, kept per the tailwind.config.ts legacy-palette note; chrome and the
// scrubber accent migrated to the token system during the port.
const WAVES = [
  { name: 'Wave 1', start: 1, end: 6, color: 'from-violet-500 to-violet-700' },
  { name: 'Wave 2', start: 7, end: 12, color: 'from-cyan-500 to-cyan-700' },
  { name: 'Wave 3', start: 13, end: 18, color: 'from-emerald-500 to-emerald-700' },
];

const MAX_MONTH = 18;

export function WaveScrubber({
  month,
  onChange,
}: {
  month: number;
  onChange: (m: number) => void;
}) {
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => {
      onChange(Math.min(MAX_MONTH, month + 1));
    }, 700);
    if (month >= MAX_MONTH) {
      setPlaying(false);
    }
    return () => clearInterval(id);
  }, [playing, month, onChange]);

  const currentWave = WAVES.find((w) => month >= w.start && month <= w.end);

  return (
    <div className="pointer-events-auto absolute bottom-24 left-1/2 z-20 w-[min(720px,calc(100vw-3rem))] -translate-x-1/2">
      <div className="rounded-2xl border border-border bg-surface-raised/70 p-4 shadow-2xl backdrop-blur-xl">
        <div className="mb-2 flex items-center justify-between text-[11px] font-semibold uppercase tracking-widest text-foreground-muted">
          <span>Transformation Timeline</span>
          <span className="text-foreground/80">
            Month {month}
            {currentWave && (
              <span className="ml-2 rounded-full bg-foreground/10 px-2 py-0.5 text-[10px] text-foreground-muted">
                {currentWave.name}
              </span>
            )}
          </span>
        </div>

        <div className="relative h-10">
          {/* Wave bands */}
          <div className="absolute inset-x-0 top-1/2 flex h-6 -translate-y-1/2 overflow-hidden rounded-full border border-border">
            {WAVES.map((w) => (
              <div
                key={w.name}
                className={`h-full bg-gradient-to-r ${w.color} opacity-30`}
                style={{ flex: w.end - w.start + 1 }}
                title={`${w.name} · Months ${w.start}-${w.end}`}
              />
            ))}
          </div>

          {/* Progress fill */}
          <div
            className="pointer-events-none absolute left-0 top-1/2 h-6 -translate-y-1/2 rounded-full bg-gradient-to-r from-primary/80 via-info/80 to-success/80 shadow-[0_0_24px_-4px_hsl(var(--primary)/0.7)] transition-all"
            style={{ width: `${(month / MAX_MONTH) * 100}%` }}
          />

          {/* Input */}
          <input
            type="range"
            min={0}
            max={MAX_MONTH}
            step={1}
            value={month}
            onChange={(e) => {
              onChange(Number(e.target.value));
              if (playing) setPlaying(false);
            }}
            className="absolute inset-0 h-full w-full cursor-pointer appearance-none bg-transparent [&::-moz-range-thumb]:h-8 [&::-moz-range-thumb]:w-8 [&::-moz-range-thumb]:cursor-grab [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:border-2 [&::-moz-range-thumb]:border-white [&::-moz-range-thumb]:bg-primary [&::-moz-range-thumb]:shadow-[0_0_18px_hsl(var(--primary)/0.8)] [&::-webkit-slider-thumb]:h-8 [&::-webkit-slider-thumb]:w-8 [&::-webkit-slider-thumb]:cursor-grab [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:border-2 [&::-webkit-slider-thumb]:border-white [&::-webkit-slider-thumb]:bg-primary [&::-webkit-slider-thumb]:shadow-[0_0_18px_hsl(var(--primary)/0.8)]"
          />
        </div>

        <div className="mt-2 flex items-center justify-between text-[10px] text-foreground-subtle">
          <span>Today</span>
          <button
            onClick={() => {
              if (month >= MAX_MONTH) {
                onChange(0);
                setPlaying(true);
              } else {
                setPlaying((p) => !p);
              }
            }}
            className="rounded-full border border-border bg-foreground/5 px-3 py-1 text-[11px] font-semibold text-foreground/80 transition-colors hover:bg-foreground/10"
          >
            {playing ? '⏸ Pause' : month >= MAX_MONTH ? '↻ Replay' : '▶ Play'}
          </button>
          <span>Month 18</span>
        </div>
      </div>
    </div>
  );
}
