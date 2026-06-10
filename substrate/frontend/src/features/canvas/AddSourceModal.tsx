import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { probeSource } from '@/api/endpoints/sources';
import type { ProbeResult } from '@/shared/types/api';

const DETECTED_LABEL: Record<string, string> = {
  openapi: 'OpenAPI / Swagger',
  odata: 'OData',
  graphql: 'GraphQL',
  json: 'JSON',
  unknown: 'Unknown format',
};

const CONF_COLOR = (c: number) =>
  c >= 0.85 ? 'text-emerald-400' : c >= 0.6 ? 'text-amber-400' : 'text-red-400';

export function AddSourceModal({ onClose }: { onClose: () => void }) {
  const [url, setUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ProbeResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleProbe = async () => {
    if (!url.trim()) return;
    setLoading(true);
    setResult(null);
    setError(null);
    try {
      const r = await probeSource(url.trim());
      setResult(r);
      if (r.error) setError(r.error);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Probe failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        exit={{ scale: 0.95, opacity: 0 }}
        className="w-[min(480px,calc(100vw-2rem))] rounded-2xl border border-border bg-surface-raised/95 p-5 shadow-2xl backdrop-blur-2xl"
      >
        <div className="mb-4 flex items-start justify-between">
          <div>
            <h2 className="text-sm font-bold tracking-wide text-foreground">Add Source</h2>
            <p className="mt-0.5 text-[11px] text-foreground-subtle">Probe a URL — the API type is identified automatically</p>
          </div>
          <button onClick={onClose} className="text-foreground-subtle hover:text-foreground/80 transition-colors">
            <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
              <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
            </svg>
          </button>
        </div>

        <div className="flex gap-2">
          <input
            type="text"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleProbe()}
            placeholder="https://api.example.com/openapi.json"
            className="flex-1 rounded-lg border border-border bg-foreground/5 px-3 py-2 text-sm text-foreground placeholder:text-foreground-subtle focus:outline-none focus:ring-1 focus:ring-primary/60"
          />
          <button
            onClick={handleProbe}
            disabled={loading || !url.trim()}
            className="rounded-lg bg-primary/20 px-4 py-2 text-sm font-semibold text-primary ring-1 ring-primary/40 transition-all hover:bg-primary/30 disabled:opacity-40"
          >
            {loading ? 'Probing…' : 'Probe'}
          </button>
        </div>

        <AnimatePresence>
          {error && !result && (
            <motion.p
              initial={{ opacity: 0, y: -4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="mt-3 rounded-lg bg-red-500/10 px-3 py-2 text-[11px] text-red-300 ring-1 ring-red-500/20"
            >
              {error}
            </motion.p>
          )}

          {result && !error && (
            <motion.div
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="mt-4 space-y-3"
            >
              <div className="flex items-center justify-between rounded-lg border border-border/60 bg-foreground/5 px-3 py-2.5">
                <div>
                  <p className="text-[11px] font-bold text-foreground/80">
                    {result.detected ? DETECTED_LABEL[result.detected] ?? result.detected : 'Unknown'}
                    {result.title && <span className="ml-2 font-normal text-foreground-muted">· {result.title}</span>}
                  </p>
                  <p className="mt-0.5 truncate text-[10px] text-foreground-subtle">{result.url}</p>
                </div>
                <span className={`text-xs font-bold ${CONF_COLOR(result.confidence)}`}>
                  {Math.round(result.confidence * 100)}%
                </span>
              </div>

              {result.hints.length > 0 && (
                <ul className="space-y-1">
                  {result.hints.map((h, i) => (
                    <li key={i} className="flex items-start gap-1.5 text-[10px] text-foreground-muted">
                      <span className="mt-0.5 text-primary">›</span>
                      {h}
                    </li>
                  ))}
                </ul>
              )}

              {result.endpoints.length > 0 && (
                <div>
                  <p className="mb-1 text-[9px] uppercase tracking-wider text-foreground-subtle">
                    Sample endpoints ({result.endpoints.length})
                  </p>
                  <div className="flex flex-wrap gap-1">
                    {result.endpoints.slice(0, 8).map((ep, i) => (
                      <span key={i} className="rounded bg-surface px-1.5 py-0.5 text-[9px] text-foreground-muted">
                        {ep}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex justify-end gap-2 pt-1">
                <button
                  onClick={onClose}
                  className="rounded-lg px-3 py-1.5 text-[11px] text-foreground-subtle hover:text-foreground-muted transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={onClose}
                  className="rounded-lg bg-primary/20 px-4 py-1.5 text-[11px] font-semibold text-primary ring-1 ring-primary/40 hover:bg-primary/30 transition-all"
                >
                  Add to project
                </button>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
  );
}
