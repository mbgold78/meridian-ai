import { useState } from 'react';
import { cn } from '@/lib/utils';
import type { Finding, SystemEntity } from '@/shared/types/api';

const SEVERITY_ORDER: Record<string, number> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
  INFO: 4,
};

// Severity colors are categorical legacy-palette data colors (see
// tailwind.config.ts) — kept; chrome migrated to the token system.
const SEV_STYLE: Record<string, string> = {
  CRITICAL: 'bg-red-500/20 text-red-200 ring-red-500/40',
  HIGH: 'bg-orange-500/20 text-orange-200 ring-orange-500/40',
  MEDIUM: 'bg-amber-500/20 text-amber-200 ring-amber-500/40',
  LOW: 'bg-emerald-500/20 text-emerald-200 ring-emerald-500/40',
  INFO: 'bg-sky-500/20 text-sky-200 ring-sky-500/40',
};

export function RisksTab({
  findings,
  systems,
  onSpotlight,
}: {
  findings: Finding[];
  systems: SystemEntity[];
  onSpotlight: (systemIds: string[]) => void;
}) {
  const [expanded, setExpanded] = useState<string | null>(null);

  const systemName = (id: string) =>
    systems.find((s) => s.id === id)?.name ?? 'Unknown system';

  const sorted = [...findings].sort(
    (a, b) =>
      (SEVERITY_ORDER[a.severity ?? 'INFO'] ?? 9) -
      (SEVERITY_ORDER[b.severity ?? 'INFO'] ?? 9)
  );

  const counts = sorted.reduce<Record<string, number>>((acc, f) => {
    const s = f.severity ?? 'INFO';
    acc[s] = (acc[s] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border px-4 py-3">
        <div className="flex items-baseline gap-2">
          <span className="text-2xl font-black text-foreground">{findings.length}</span>
          <span className="text-xs uppercase tracking-widest text-foreground-muted">
            findings across your ecosystem
          </span>
        </div>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((sev) =>
            counts[sev] ? (
              <span
                key={sev}
                className={cn('rounded-full px-2 py-0.5 text-[10px] font-bold ring-1', SEV_STYLE[sev])}
              >
                {counts[sev]} {sev}
              </span>
            ) : null
          )}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-3">
        <div className="flex flex-col gap-2">
          {sorted.map((f) => {
            const isOpen = expanded === f.id;
            const sev = f.severity ?? 'INFO';
            return (
              <div
                key={f.id}
                data-testid="canvas-finding-row"
                data-finding-id={f.id}
                className="overflow-hidden rounded-lg border border-border/50 bg-foreground/[0.03] transition-colors hover:bg-foreground/[0.06]"
              >
                <button
                  onClick={() => {
                    setExpanded(isOpen ? null : f.id);
                    onSpotlight(f.affectedSystemIds ?? []);
                  }}
                  className="w-full px-3 py-2.5 text-left"
                >
                  <div className="flex items-start gap-2">
                    <span
                      className={cn(
                        'mt-0.5 shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold ring-1',
                        SEV_STYLE[sev]
                      )}
                    >
                      {sev}
                    </span>
                    <div className="flex-1 min-w-0">
                      <p className="text-[13px] font-semibold text-foreground/90">{f.title}</p>
                      <p className="mt-0.5 text-[11px] text-foreground-muted">{f.findingType}</p>
                    </div>
                  </div>
                </button>
                {isOpen && (
                  <div
                    data-testid="canvas-finding-detail"
                    className="border-t border-border/50 bg-foreground/[0.02] px-3 py-2.5"
                  >
                    <p className="text-[12px] leading-relaxed text-foreground-muted">{f.description}</p>
                    {f.recommendation && (
                      <p className="mt-2 rounded border-l-2 border-primary bg-primary/5 px-2 py-1.5 text-[11px] italic text-primary">
                        {f.recommendation}
                      </p>
                    )}
                    {f.affectedSystemIds && f.affectedSystemIds.length > 0 && (
                      <div className="mt-2">
                        <p className="text-[10px] uppercase tracking-widest text-foreground-subtle">
                          Affects
                        </p>
                        <div className="mt-1 flex flex-wrap gap-1">
                          {f.affectedSystemIds.map((id) => (
                            <span
                              key={id}
                              className="rounded-full bg-foreground/10 px-2 py-0.5 text-[10px] text-foreground-muted"
                            >
                              {systemName(id)}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
