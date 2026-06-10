import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useTheme } from '@/app/theme';

// Storage key renamed from homepoc's 'meridian-canvas-welcomed-v1' — substrate
// keys are product-prefixed, and the brand name is workspace-configurable.
const STORAGE_KEY = 'substrate-canvas-welcomed-v1';

export function WelcomeOverlay() {
  const { config } = useTheme();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const seen = window.localStorage.getItem(STORAGE_KEY);
    if (!seen) setOpen(true);
  }, []);

  const dismiss = () => {
    try {
      window.localStorage.setItem(STORAGE_KEY, '1');
    } catch {
      /* ignore */
    }
    setOpen(false);
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="pointer-events-auto absolute inset-0 z-40 flex items-center justify-center bg-background/60 backdrop-blur-sm"
          onClick={dismiss}
        >
          <motion.div
            initial={{ scale: 0.96, y: 8 }}
            animate={{ scale: 1, y: 0 }}
            exit={{ scale: 0.96, y: 8 }}
            transition={{ type: 'spring', damping: 22, stiffness: 260 }}
            onClick={(e) => e.stopPropagation()}
            className="w-[min(560px,calc(100vw-2rem))] rounded-2xl border border-border bg-surface-raised p-7 shadow-2xl ring-1 ring-border"
          >
            <p className="text-[10px] font-bold uppercase tracking-[0.3em] text-primary">
              Welcome to {config.appDisplayName}
            </p>
            <h2 className="mt-2 text-2xl font-black text-foreground">
              This is your bank's risk stack, on one screen.
            </h2>
            <p className="mt-3 text-[14px] leading-relaxed text-foreground-muted">
              Every tile is a system you run. Lines between them are data flows. Red badges
              count open risks. Amber tiles are manual processes people do by hand —
              the 🧠 means tribal knowledge (one person knows how it works).
            </p>

            <div className="mt-5 grid grid-cols-3 gap-3">
              <Step
                n={1}
                title="Today"
                body="See what you run right now."
              />
              <Step
                n={2}
                title="Target"
                body="See what it looks like after consolidation."
              />
              <Step
                n={3}
                title="Waves"
                body="Scrub the timeline. Watch systems retire."
              />
            </div>

            <p className="mt-5 text-[12px] text-foreground-muted">
              Ask anything in the chat bar at the bottom — {config.appDisplayName} will
              highlight what it's talking about on the canvas.
            </p>

            <div className="mt-6 flex items-center justify-end gap-2">
              <button
                onClick={dismiss}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-bold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:scale-[1.02]"
              >
                Let's go →
              </button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

function Step({ n, title, body }: { n: number; title: string; body: string }) {
  return (
    <div className="rounded-lg border border-border bg-foreground/[0.03] p-3">
      <div className="flex items-center gap-2">
        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-[10px] font-black text-primary-foreground">
          {n}
        </span>
        <span className="text-[12px] font-bold text-foreground">{title}</span>
      </div>
      <p className="mt-1.5 text-[11px] leading-snug text-foreground-muted">{body}</p>
    </div>
  );
}
