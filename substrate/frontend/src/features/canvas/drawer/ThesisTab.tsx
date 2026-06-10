import { useState } from 'react';
import { cn } from '@/lib/utils';
import type { ReductionThesis } from '@/shared/types/api';

export function ThesisTab({
  thesis,
  loading,
  onSpotlight,
}: {
  thesis: ReductionThesis | undefined;
  loading: boolean;
  onSpotlight: (systemIds: string[]) => void;
}) {
  const [tab, setTab] = useState<'retire' | 'keep'>('retire');

  if (loading || !thesis) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-sm text-foreground-muted">Running reduction…</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      {/* Verdict */}
      <div className="border-b border-border bg-gradient-to-br from-primary/15 to-transparent px-4 py-4">
        <p className="text-[10px] font-bold uppercase tracking-[0.3em] text-primary">
          The verdict
        </p>
        <h2 className="mt-1.5 text-[15px] font-bold leading-snug text-foreground">
          {thesis.headline}
        </h2>
        <p className="mt-1.5 text-[12px] leading-relaxed text-foreground-muted">{thesis.subhead}</p>
        <div className="mt-3 flex items-center gap-2">
          <Pill value={thesis.systemsBefore} label="have" />
          <span className="text-foreground-subtle">→</span>
          <Pill value={thesis.systemsKeep} label="keep" tone="keep" />
          <Pill value={thesis.systemsRetire} label="cut" tone="retire" />
          <Pill value={`$${thesis.annualSavings}`} label="/yr" tone="save" />
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border">
        <TabButton
          active={tab === 'retire'}
          onClick={() => {
            setTab('retire');
            onSpotlight(thesis.retireList.map((r) => r.id));
          }}
          label="Cut list"
          count={thesis.retireList.length}
          tone="retire"
        />
        <TabButton
          active={tab === 'keep'}
          onClick={() => {
            setTab('keep');
            onSpotlight(thesis.keepList.map((k) => k.id));
          }}
          label="Keep list"
          count={thesis.keepList.length}
          tone="keep"
        />
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto px-3 py-3">
        {tab === 'retire' && (
          <div className="flex flex-col gap-2">
            {thesis.retireList.map((entry) => (
              <div
                key={entry.id}
                onMouseEnter={() => onSpotlight([entry.id])}
                className="rounded-lg border border-red-500/20 bg-red-500/[0.04] px-3 py-2.5 transition-colors hover:bg-red-500/[0.08]"
              >
                <div className="flex items-start gap-2">
                  <span className="mt-0.5 shrink-0 rounded-full bg-red-500/20 px-1.5 py-0.5 text-[9px] font-bold text-red-200 ring-1 ring-red-500/40">
                    CUT
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline justify-between gap-2">
                      <p className="truncate text-[13px] font-semibold text-foreground">
                        {entry.name}
                      </p>
                      <span className="shrink-0 text-[11px] font-black text-emerald-300">
                        ${fmtUsd(entry.annualSavingsUsd)}/yr
                      </span>
                    </div>
                    {(entry.vendor || entry.businessLine) && (
                      <p className="mt-0.5 truncate text-[10px] text-foreground-muted">
                        {[entry.vendor, entry.businessLine].filter(Boolean).join(' · ')}
                      </p>
                    )}
                    {entry.absorbedBy && (
                      <p className="mt-1 text-[11px] font-medium text-primary">
                        → absorbed by <span className="font-bold">{entry.absorbedBy}</span>
                      </p>
                    )}
                    <ul className="mt-2 space-y-1">
                      {entry.reasons.map((r, i) => (
                        <li key={i} className="flex items-start gap-1.5 text-[11px] text-foreground-muted">
                          <span className="mt-[3px] h-1 w-1 shrink-0 rounded-full bg-red-400" />
                          {r}
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              </div>
            ))}
            {thesis.retireList.length === 0 && (
              <p className="mt-8 text-center text-sm text-foreground-subtle">
                No clear retirement candidates — stack is lean, or we need more evidence.
              </p>
            )}
          </div>
        )}

        {tab === 'keep' && (
          <div className="flex flex-col gap-2">
            {thesis.keepList.map((entry) => (
              <div
                key={entry.id}
                onMouseEnter={() => onSpotlight([entry.id])}
                className="rounded-lg border border-emerald-500/20 bg-emerald-500/[0.04] px-3 py-2.5 transition-colors hover:bg-emerald-500/[0.08]"
              >
                <div className="flex items-start gap-2">
                  <span className="mt-0.5 shrink-0 rounded-full bg-emerald-500/20 px-1.5 py-0.5 text-[9px] font-bold text-emerald-200 ring-1 ring-emerald-500/40">
                    KEEP
                  </span>
                  <div className="flex-1 min-w-0">
                    <p className="truncate text-[13px] font-semibold text-foreground">{entry.name}</p>
                    {(entry.vendor || entry.businessLine) && (
                      <p className="mt-0.5 truncate text-[10px] text-foreground-muted">
                        {[entry.vendor, entry.businessLine].filter(Boolean).join(' · ')}
                      </p>
                    )}
                    <ul className="mt-2 space-y-1">
                      {entry.reasons.map((r, i) => (
                        <li key={i} className="flex items-start gap-1.5 text-[11px] text-foreground-muted">
                          <span className="mt-[3px] h-1 w-1 shrink-0 rounded-full bg-emerald-400" />
                          {r}
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Risks footer */}
      {thesis.keyRisks.length > 0 && (
        <div className="border-t border-border bg-amber-500/[0.04] px-4 py-3">
          <p className="text-[10px] font-bold uppercase tracking-widest text-amber-300">
            Before you cut
          </p>
          <ul className="mt-1.5 space-y-1">
            {thesis.keyRisks.map((r, i) => (
              <li
                key={i}
                className="flex items-start gap-1.5 text-[11px] leading-snug text-foreground-muted"
              >
                <span className="mt-[3px] h-1.5 w-1.5 shrink-0 rounded-full bg-amber-400" />
                {r}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function Pill({
  value,
  label,
  tone = 'default',
}: {
  value: number | string;
  label: string;
  tone?: 'default' | 'keep' | 'retire' | 'save';
}) {
  return (
    <div
      className={cn(
        'inline-flex items-baseline gap-1 rounded-md px-2 py-1',
        tone === 'default' && 'bg-foreground/5 text-foreground-muted',
        tone === 'keep' && 'bg-emerald-500/15 text-emerald-200 ring-1 ring-emerald-500/30',
        tone === 'retire' && 'bg-red-500/15 text-red-200 ring-1 ring-red-500/30',
        tone === 'save' && 'bg-primary/15 text-primary ring-1 ring-primary/30'
      )}
    >
      <span className="text-sm font-black">{value}</span>
      <span className="text-[10px] font-medium opacity-70">{label}</span>
    </div>
  );
}

function TabButton({
  active,
  onClick,
  label,
  count,
  tone,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  count: number;
  tone: 'keep' | 'retire';
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'flex-1 border-b-2 px-3 py-2 text-left text-[12px] font-semibold transition-colors',
        active
          ? tone === 'retire'
            ? 'border-red-400 text-foreground'
            : 'border-emerald-400 text-foreground'
          : 'border-transparent text-foreground-subtle hover:text-foreground-muted'
      )}
    >
      {label}
      <span
        className={cn(
          'ml-1.5 rounded-full px-1.5 py-0.5 text-[10px]',
          tone === 'retire' ? 'bg-red-500/20 text-red-300' : 'bg-emerald-500/20 text-emerald-300'
        )}
      >
        {count}
      </span>
    </button>
  );
}

function fmtUsd(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000) return `${Math.round(v / 1_000)}K`;
  return String(Math.round(v));
}
