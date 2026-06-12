import { motion } from 'framer-motion';
import { cn } from '@/lib/utils';
import type { AssistantStatus } from '@/shared/types/api';

export function CompletenessBadge({
  status,
  active,
  onClick,
}: {
  status: AssistantStatus | undefined;
  active: boolean;
  onClick: () => void;
}) {
  if (!status) return null;

  const pct = status.completenessPercent;
  const blockerCount = status.blockers.length;
  const ready = status.readyForReduction;

  return (
    <motion.button
      data-testid="completeness-badge"
      layout
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      onClick={onClick}
      className={cn(
        'pointer-events-auto absolute left-1/2 top-[112px] z-10 flex -translate-x-1/2 items-center gap-3 rounded-full border bg-surface-raised/80 px-4 py-2 shadow-xl backdrop-blur-xl transition-colors',
        active
          ? 'border-primary/50 ring-1 ring-primary/40'
          : 'border-border hover:border-border-strong'
      )}
    >
      {/* Progress ring */}
      <div className="relative h-7 w-7 shrink-0">
        <svg viewBox="0 0 28 28" className="h-full w-full -rotate-90">
          <circle
            cx="14"
            cy="14"
            r="11"
            fill="none"
            stroke="hsl(var(--foreground) / 0.12)"
            strokeWidth="3"
          />
          <circle
            cx="14"
            cy="14"
            r="11"
            fill="none"
            stroke={
              ready
                ? 'hsl(var(--success))'
                : blockerCount > 0
                ? 'hsl(var(--destructive))'
                : 'hsl(var(--info))'
            }
            strokeWidth="3"
            strokeDasharray={`${(pct / 100) * 69.1} 69.1`}
            strokeLinecap="round"
            className="transition-all duration-700"
          />
        </svg>
        <span className="absolute inset-0 flex items-center justify-center text-[9px] font-black text-foreground">
          {pct}
        </span>
      </div>

      {/* Label */}
      <div className="flex items-baseline gap-2 text-left">
        <span className="text-[12px] font-bold text-foreground">
          {ready ? 'Ready to reduce' : 'Project health'}
        </span>
        {blockerCount > 0 && (
          <span className="rounded-full bg-red-500/20 px-1.5 py-0.5 text-[10px] font-bold text-red-200 ring-1 ring-red-500/40">
            {blockerCount} blocker{blockerCount === 1 ? '' : 's'}
          </span>
        )}
        <span className="text-[11px] text-primary">
          {active ? 'Hide' : 'Continue'} →
        </span>
      </div>
    </motion.button>
  );
}
