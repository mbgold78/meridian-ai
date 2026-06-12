import { memo } from 'react';
import { Handle, Position, type NodeProps, type Node } from '@xyflow/react';
import { cn } from '@/lib/utils';

export interface CanvasProcessNodeData {
  label: string;
  classification: string | null;
  riskRating: string | null;
  tribal: boolean;
  retired: boolean;
  spotlight: boolean;
  [key: string]: unknown;
}

type NodeType = Node<CanvasProcessNodeData, 'canvasProcess'>;

function CanvasProcessNodeComponent({ data }: NodeProps<NodeType>) {
  const high = data.riskRating === 'HIGH' || data.riskRating === 'CRITICAL';

  return (
    <>
      <Handle
        type="target"
        position={Position.Left}
        className="!h-1.5 !w-1.5 !border-0 !bg-foreground/30"
      />

      {data.spotlight && (
        <span
          className="pointer-events-none absolute -inset-2 rounded-xl ring-2 ring-amber-300 animate-[pulse_1.4s_ease-in-out_infinite]"
          aria-hidden
        />
      )}

      <div
        data-testid="canvas-process-node"
        className={cn(
          'relative w-[200px] rounded-xl border border-dashed border-amber-400/40 bg-amber-500/5 px-3 py-2.5',
          high && !data.retired && 'shadow-[0_0_18px_-4px_rgba(251,191,36,0.45)]',
          data.retired && 'opacity-20 grayscale',
          'transition-all duration-500'
        )}
      >
        <div className="flex items-start gap-2">
          <span className="shrink-0 rounded-md bg-amber-500/30 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-widest text-amber-200">
            Manual
          </span>
          {data.tribal && (
            <span className="text-sm leading-none" title="Tribal knowledge" aria-hidden>
              🧠
            </span>
          )}
          {high && (
            <span className="ml-auto rounded-full bg-red-500/30 px-1.5 py-0.5 text-[9px] font-bold text-red-200">
              HIGH RISK
            </span>
          )}
        </div>
        <p className="mt-1.5 text-[12px] font-semibold leading-snug text-amber-50 line-clamp-2">
          {data.label}
        </p>
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="!h-1.5 !w-1.5 !border-0 !bg-foreground/30"
      />
    </>
  );
}

export const CanvasProcessNode = memo(CanvasProcessNodeComponent);
