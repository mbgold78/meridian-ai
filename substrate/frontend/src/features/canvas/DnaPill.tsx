import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useApiQuery } from '@/shared/hooks/useApi';
import { getDna } from '@/api/endpoints/dna';
import type { DnaConfig } from '@/shared/types/api';

export function DnaPill() {
  const [open, setOpen] = useState(false);
  const { data: dna } = useApiQuery<DnaConfig>(['dna'], getDna);

  return (
    <div className="pointer-events-auto absolute left-4 top-16 z-20">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1.5 rounded-full border border-primary/20 bg-surface-raised/60 px-3 py-1 text-[10px] font-semibold uppercase tracking-widest text-primary/70 backdrop-blur-xl transition-all hover:border-primary/50 hover:text-primary"
      >
        <span className="h-1.5 w-1.5 rounded-full bg-primary/80" />
        DNA
      </button>

      <AnimatePresence>
        {open && dna && (
          <motion.div
            initial={{ opacity: 0, y: -4, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.97 }}
            className="mt-2 w-72 rounded-xl border border-border bg-surface-raised/90 p-4 shadow-2xl backdrop-blur-2xl"
          >
            <p className="mb-2 text-[9px] uppercase tracking-widest text-foreground-subtle">Project DNA</p>
            <p className="text-[11px] font-bold text-foreground/90">{dna.projectName}</p>
            <p className="mt-0.5 text-[10px] text-foreground-muted">{dna.domain}</p>

            <div className="mt-3 space-y-1.5">
              <Row label="Objective" value={dna.primaryObjective} />
              <Row label="Mode" value={dna.reductionMode} />
              <Row label="Horizon" value={`${dna.targetHorizonMonths} months`} />
              <Row label="Entities" value={dna.entityTypes.join(', ')} />
              <Row label="Regulatory" value={dna.regulatoryContext.join(', ')} />
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-2">
      <span className="w-16 shrink-0 text-[9px] uppercase tracking-wider text-foreground-subtle">{label}</span>
      <span className="text-[10px] text-foreground-muted leading-tight">{value}</span>
    </div>
  );
}
