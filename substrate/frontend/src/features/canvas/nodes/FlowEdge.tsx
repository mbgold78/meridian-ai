import { memo } from 'react';
import { BaseEdge, getBezierPath, type EdgeProps } from '@xyflow/react';

export interface FlowEdgeData {
  retired?: boolean;
  emphasized?: boolean;
  [key: string]: unknown;
}

// Edge colors are token-backed (info = data-flow, warning = spotlight) so the
// canvas follows BrandingConfig re-toning; SVG presentation attributes accept
// var()-based values in all supported browsers.
function FlowEdgeComponent(props: EdgeProps) {
  const {
    id,
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    data,
  } = props;

  const d = (data ?? {}) as FlowEdgeData;
  const retired = Boolean(d.retired);
  const emphasized = Boolean(d.emphasized);

  const [edgePath] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const stroke = retired
    ? 'hsl(var(--foreground) / 0.12)'
    : emphasized
    ? 'hsl(var(--warning) / 0.85)'
    : 'hsl(var(--info) / 0.4)';

  const strokeWidth = emphasized ? 2 : 1.25;

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          stroke,
          strokeWidth,
          transition: 'stroke 400ms ease, stroke-width 400ms ease',
        }}
      />
      {!retired && (
        <circle
          r={emphasized ? 3 : 2}
          fill={emphasized ? 'hsl(var(--warning))' : 'hsl(var(--info))'}
        >
          <animateMotion
            dur={emphasized ? '2.4s' : '3.6s'}
            repeatCount="indefinite"
            path={edgePath}
          />
        </circle>
      )}
    </>
  );
}

export const FlowEdge = memo(FlowEdgeComponent);
