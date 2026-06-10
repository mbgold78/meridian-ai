import { motion } from 'framer-motion';
import { cn } from '@/lib/utils';

export type Lens = 'today' | 'target' | 'waves';

const LENSES: {
  key: Lens;
  label: string;
  hint: string;
  icon: JSX.Element;
}[] = [
  {
    key: 'today',
    label: 'Today',
    hint: 'What you run right now',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}>
        <circle cx="12" cy="12" r="8" />
        <path d="M12 8v4l2.5 2" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    key: 'target',
    label: 'Target',
    hint: 'What it becomes',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}>
        <circle cx="12" cy="12" r="8" />
        <circle cx="12" cy="12" r="4" />
        <circle cx="12" cy="12" r="1.2" fill="currentColor" />
      </svg>
    ),
  },
  {
    key: 'waves',
    label: 'Waves',
    hint: 'How we get there',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}>
        <path
          d="M3 12c2-3 4-3 6 0s4 3 6 0 4-3 6 0"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M3 17c2-3 4-3 6 0s4 3 6 0 4-3 6 0"
          strokeLinecap="round"
          strokeLinejoin="round"
          opacity="0.5"
        />
      </svg>
    ),
  },
];

export function LensRail({ lens, onChange }: { lens: Lens; onChange: (l: Lens) => void }) {
  return (
    <div className="pointer-events-auto absolute left-4 top-1/2 z-20 -translate-y-1/2">
      <div className="flex flex-col gap-2 rounded-2xl border border-border bg-surface-raised/60 p-2 shadow-2xl backdrop-blur-xl">
        {LENSES.map((l) => {
          const active = lens === l.key;
          return (
            <button
              key={l.key}
              onClick={() => onChange(l.key)}
              className={cn(
                'group relative flex h-16 w-16 flex-col items-center justify-center rounded-xl transition-all',
                active
                  ? 'bg-gradient-to-br from-primary/40 to-primary/10 text-primary ring-1 ring-primary/60'
                  : 'text-foreground-muted hover:bg-foreground/5 hover:text-foreground'
              )}
              aria-label={l.label}
            >
              {active && (
                <motion.span
                  layoutId="lens-active"
                  className="absolute inset-0 rounded-xl ring-2 ring-primary/70 shadow-[0_0_24px_-4px_hsl(var(--primary)/0.6)]"
                  transition={{ type: 'spring', damping: 22, stiffness: 260 }}
                />
              )}
              <span className="relative h-6 w-6">{l.icon}</span>
              <span className="relative mt-1 text-[10px] font-semibold uppercase tracking-widest">
                {l.label}
              </span>
              <span className="pointer-events-none absolute left-[72px] top-1/2 -translate-y-1/2 whitespace-nowrap rounded-md bg-surface-raised/95 px-2 py-1 text-[11px] font-medium text-foreground/80 opacity-0 shadow-lg ring-1 ring-border transition-opacity group-hover:opacity-100">
                {l.hint}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
