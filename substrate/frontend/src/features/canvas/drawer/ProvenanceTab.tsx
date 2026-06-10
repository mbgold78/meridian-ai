import { useApiQuery } from '@/shared/hooks/useApi';
import { getEvidenceForEntity, getEvidenceForDomain } from '@/api/endpoints/evidence';
import type { EvidenceChainEntry, SystemEntity } from '@/shared/types/api';
import { LoadingSpinner } from '@/shared/components';

function ConfidencePip({ confidence }: { confidence: number | null }) {
  if (confidence == null) return null;
  const pct = Math.round(confidence * 100);
  const colour =
    pct >= 90 ? 'bg-emerald-400' : pct >= 70 ? 'bg-amber-400' : 'bg-red-400';
  return (
    <span className={`inline-block h-1.5 w-1.5 rounded-full ${colour}`} title={`${pct}% confidence`} />
  );
}

function EntryCard({ entry }: { entry: EvidenceChainEntry }) {
  const { evidence, document } = entry;
  return (
    <div className="rounded-lg border border-border/60 bg-foreground/5 p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <p className="truncate text-[11px] font-semibold text-foreground/90">
            {evidence.label ?? evidence.entityType}
          </p>
          <p className="mt-0.5 truncate text-[10px] text-foreground-subtle">
            {document?.sourceFile ?? evidence.sourceFile ?? 'unknown source'}
            {document?.documentType ? ` · ${document.documentType.replace('_', ' ')}` : ''}
          </p>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <ConfidencePip confidence={evidence.confidence} />
          <span className="rounded bg-surface px-1.5 py-0.5 text-[9px] uppercase tracking-wider text-foreground-subtle">
            {evidence.extractor ?? 'converter'}
          </span>
        </div>
      </div>
      {document && (
        <p className="mt-1.5 truncate text-[9px] text-foreground-subtle/70">
          sha256: {document.sourceFileSha256.slice(0, 16)}…
        </p>
      )}
    </div>
  );
}

export function ProvenanceTab({
  domainId,
  selectedSystem,
}: {
  /** Workspace-resolved domain id (seam S14 — replaces homepoc's DOMAIN_ID). */
  domainId: string;
  selectedSystem: SystemEntity | null;
}) {
  const entityQuery = useApiQuery<EvidenceChainEntry[]>(
    ['provenance-entity', selectedSystem?.id ?? ''],
    () => getEvidenceForEntity(selectedSystem!.id),
    { enabled: !!selectedSystem }
  );

  const domainQuery = useApiQuery<EvidenceChainEntry[]>(
    ['provenance-domain', domainId],
    () => getEvidenceForDomain(domainId),
    { enabled: !selectedSystem }
  );

  const loading = selectedSystem ? entityQuery.isLoading : domainQuery.isLoading;
  const entries = (selectedSystem ? entityQuery.data : domainQuery.data) ?? [];

  // Group by source file
  const byFile = new Map<string, EvidenceChainEntry[]>();
  for (const e of entries) {
    const key = e.evidence.sourceFile ?? 'unknown';
    if (!byFile.has(key)) byFile.set(key, []);
    byFile.get(key)!.push(e);
  }

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border/60 px-4 py-3">
        <h3 className="text-[11px] font-bold uppercase tracking-widest text-foreground-muted">
          {selectedSystem ? `Provenance · ${selectedSystem.name}` : 'Provenance · All sources'}
        </h3>
        <p className="mt-0.5 text-[10px] text-foreground-subtle">
          {selectedSystem
            ? 'Where this system record came from'
            : 'Source chain for all ingested entities'}
        </p>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-4">
        {loading && (
          <div className="flex justify-center py-8">
            <LoadingSpinner size="sm" />
          </div>
        )}

        {!loading && entries.length === 0 && (
          <p className="py-8 text-center text-[11px] text-foreground-subtle">
            No provenance records found.
          </p>
        )}

        {!loading && [...byFile.entries()].map(([file, fileEntries]) => (
          <div key={file}>
            <div className="mb-1.5 flex items-center gap-2">
              <span className="h-px flex-1 bg-border/60" />
              <span className="text-[9px] uppercase tracking-wider text-foreground-subtle">{file}</span>
              <span className="h-px flex-1 bg-border/60" />
            </div>
            <div className="space-y-1.5">
              {fileEntries.map((e) => (
                <EntryCard key={e.evidence.id} entry={e} />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
