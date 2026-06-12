import { useState } from 'react';
import { useApiMutation } from '@/shared/hooks/useApi';
import { verifyDecisionLog } from '@/api/endpoints/decisions';
import type { DesignDecision, HashChainVerification } from '@/shared/types/api';
import { cn } from '@/lib/utils';

const STATUS_STYLE: Record<string, string> = {
  APPROVED: 'bg-emerald-500/20 text-emerald-200 ring-emerald-500/40',
  PROPOSED: 'bg-sky-500/20 text-sky-200 ring-sky-500/40',
  DRAFT: 'bg-foreground/10 text-foreground-muted ring-border',
  REJECTED: 'bg-red-500/20 text-red-200 ring-red-500/40',
  SUPERSEDED: 'bg-zinc-500/20 text-zinc-300 ring-zinc-500/40',
};

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  } catch {
    return iso;
  }
}

function shortHash(h: string | null): string {
  if (!h) return '—';
  return h.slice(0, 8);
}

export function DecisionsTab({
  domainId,
  decisions,
}: {
  /** Workspace-resolved domain id (seam S14 — replaces homepoc's DOMAIN_ID). */
  domainId: string;
  decisions: DesignDecision[];
}) {
  const [verification, setVerification] = useState<HashChainVerification | null>(null);
  const verifyMut = useApiMutation((d: string) => verifyDecisionLog(d));

  const runVerify = () => {
    verifyMut.mutate(domainId, { onSuccess: (r) => setVerification(r) });
  };

  return (
    <div className="flex h-full flex-col">
      {/* data-testid (DOC-1 S4, additive only): the header — count, verify
          button, verify-result banner — is the deterministic capture crop;
          the rows below render per-boot chain-hash prefixes and can never be
          byte-stable across fresh seeds. */}
      <div data-testid="decision-log-header" className="border-b border-border px-4 py-3">
        <div className="flex items-center justify-between">
          <div>
            <span className="text-2xl font-black text-foreground">{decisions.length}</span>
            <span className="ml-2 text-xs uppercase tracking-widest text-foreground-muted">
              decisions on record
            </span>
          </div>
          <button
            onClick={runVerify}
            disabled={verifyMut.isPending}
            className="rounded-full bg-primary/80 px-3 py-1 text-[11px] font-semibold text-primary-foreground hover:bg-primary disabled:opacity-50"
          >
            {verifyMut.isPending ? 'Verifying…' : 'Verify chain'}
          </button>
        </div>
        {verification && (
          <div
            data-testid="decision-verify-result"
            data-valid={verification.valid}
            className={cn(
              'mt-2 rounded-md px-3 py-1.5 text-[11px] font-medium ring-1',
              verification.valid
                ? 'bg-emerald-500/10 text-emerald-200 ring-emerald-500/30'
                : 'bg-red-500/10 text-red-200 ring-red-500/30'
            )}
          >
            {verification.valid ? '✓' : '✗'} {verification.message} · {verification.verifiedLinks}/
            {verification.totalDecisions} links
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-3">
        {decisions.length === 0 ? (
          <p className="mt-12 text-center text-sm text-foreground-subtle">No decisions yet.</p>
        ) : (
          <div className="flex flex-col gap-2">
            {decisions.map((d, idx) => {
              const style = STATUS_STYLE[d.status] ?? STATUS_STYLE.DRAFT;
              return (
                <div
                  key={d.id}
                  data-testid="decision-log-row"
                  data-decision-id={d.id}
                  className="rounded-lg border border-border/50 bg-foreground/[0.03] px-3 py-2.5"
                >
                  <div className="flex items-start gap-2">
                    <span className="mt-0.5 shrink-0 rounded-full bg-foreground/10 px-1.5 py-0.5 font-mono text-[9px] text-foreground-muted">
                      #{decisions.length - idx}
                    </span>
                    <div className="flex-1 min-w-0">
                      <p className="text-[13px] font-semibold text-foreground/90">{d.title}</p>
                      <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[10px]">
                        <span className={cn('rounded-full px-1.5 py-0.5 font-bold ring-1', style)}>
                          {d.status}
                        </span>
                        <span className="text-foreground-subtle">{d.decisionType}</span>
                        <span className="text-foreground-subtle">·</span>
                        <span className="text-foreground-subtle">{formatDate(d.createdAt)}</span>
                      </div>
                      {d.description && (
                        <p className="mt-1.5 text-[11px] leading-snug text-foreground-muted line-clamp-2">
                          {d.description}
                        </p>
                      )}
                      <div className="mt-1.5 flex items-center gap-2 font-mono text-[10px] text-foreground-subtle">
                        <span>prev {shortHash(d.previousHash)}</span>
                        <span>→</span>
                        <span className="text-primary">{shortHash(d.currentHash)}</span>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
