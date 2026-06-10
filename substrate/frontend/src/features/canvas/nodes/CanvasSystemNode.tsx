import { memo } from 'react';
import { Handle, Position, type NodeProps, type Node } from '@xyflow/react';
import { cn } from '@/lib/utils';

// System-type accents (sky/teal/violet/zinc) are categorical data colors from
// the legacy Living Canvas palette (see tailwind.config.ts note) — kept as-is;
// the chrome (slate/meridian) migrated to the token system during the port.
const TYPE_STYLE: Record<
  string,
  { accent: string; pillBg: string; pillText: string; label: string }
> = {
  core: {
    accent: 'border-l-sky-400',
    pillBg: 'bg-sky-500/20',
    pillText: 'text-sky-200',
    label: 'Core',
  },
  ancillary: {
    accent: 'border-l-teal-400',
    pillBg: 'bg-teal-500/20',
    pillText: 'text-teal-200',
    label: 'Ancillary',
  },
  vendor: {
    accent: 'border-l-violet-400',
    pillBg: 'bg-violet-500/20',
    pillText: 'text-violet-200',
    label: 'Vendor',
  },
  legacy: {
    accent: 'border-l-zinc-400',
    pillBg: 'bg-zinc-500/20',
    pillText: 'text-zinc-300',
    label: 'Legacy',
  },
};

export interface CanvasSystemNodeData {
  label: string;
  vendor: string | null;
  systemType: string | null;
  status: string | null;
  businessLine: string | null;
  findingCount: number;
  criticalCount: number;
  retired: boolean;
  spotlight: boolean;
  [key: string]: unknown;
}

type NodeType = Node<CanvasSystemNodeData, 'canvasSystem'>;

function CanvasSystemNodeComponent({ data, selected }: NodeProps<NodeType>) {
  const style =
    TYPE_STYLE[(data.systemType ?? 'legacy').toLowerCase()] ?? TYPE_STYLE.legacy;
  const hasFindings = data.findingCount > 0;
  const critical = data.criticalCount > 0;

  return (
    <>
      <Handle
        type="target"
        position={Position.Left}
        className="!h-1.5 !w-1.5 !border-0 !bg-foreground/40"
      />

      {critical && !data.retired && (
        <span
          className="pointer-events-none absolute -inset-2 rounded-2xl bg-red-500/15 blur-md animate-pulse"
          aria-hidden
        />
      )}
      {data.spotlight && (
        <span
          className="pointer-events-none absolute -inset-3 rounded-2xl ring-2 ring-amber-300 animate-[pulse_1.4s_ease-in-out_infinite]"
          aria-hidden
        />
      )}

      <div
        className={cn(
          'relative w-[240px] rounded-xl border-l-4 bg-surface-raised/90 ring-1 ring-border backdrop-blur-sm shadow-xl',
          style.accent,
          data.retired && 'opacity-25 grayscale saturate-0',
          selected && 'ring-2 ring-primary',
          'transition-all duration-500'
        )}
      >
        <div className="px-3.5 py-3">
          {/* Title row */}
          <div className="flex items-start gap-2">
            <div className="flex-1 min-w-0">
              <p className="truncate text-[15px] font-bold leading-tight text-foreground">
                {data.label}
              </p>
              {data.vendor && (
                <p className="mt-0.5 truncate text-[11px] text-foreground-muted">
                  {data.vendor}
                </p>
              )}
            </div>

            {hasFindings && !data.retired && (
              <span
                className={cn(
                  'inline-flex h-5 min-w-[20px] shrink-0 items-center justify-center rounded-full px-1 text-[10px] font-bold leading-none',
                  critical
                    ? 'bg-red-500 text-white shadow-[0_0_8px_rgba(239,68,68,0.7)]'
                    : 'bg-orange-500/90 text-white'
                )}
                title={`${data.findingCount} finding${data.findingCount > 1 ? 's' : ''}`}
              >
                {data.findingCount}
              </span>
            )}
          </div>

          {/* Meta row */}
          <div className="mt-2 flex flex-wrap items-center gap-1.5">
            <span
              className={cn(
                'rounded-full px-1.5 py-0.5 text-[10px] font-semibold',
                style.pillBg,
                style.pillText
              )}
            >
              {style.label}
            </span>
            {data.businessLine && (
              <span className="truncate rounded-full bg-foreground/5 px-1.5 py-0.5 text-[10px] text-foreground-muted">
                {data.businessLine}
              </span>
            )}
            <span
              className={cn(
                'ml-auto inline-flex items-center gap-1 text-[10px] font-medium',
                data.status === 'active' && 'text-emerald-300',
                data.status === 'deprecated' && 'text-red-300',
                data.status === 'in-review' && 'text-amber-300',
                !data.status && 'text-foreground-subtle'
              )}
            >
              <span
                className={cn(
                  'inline-block h-1.5 w-1.5 rounded-full',
                  data.status === 'active' && 'bg-emerald-400',
                  data.status === 'deprecated' && 'bg-red-400',
                  data.status === 'in-review' && 'bg-amber-400',
                  !data.status && 'bg-foreground/40'
                )}
              />
              {data.status ?? 'unknown'}
            </span>
          </div>
        </div>
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="!h-1.5 !w-1.5 !border-0 !bg-foreground/40"
      />
    </>
  );
}

export const CanvasSystemNode = memo(CanvasSystemNodeComponent);
