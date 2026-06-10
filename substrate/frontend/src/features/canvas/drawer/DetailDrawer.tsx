import { AnimatePresence, motion } from 'framer-motion';
import { cn } from '@/lib/utils';
import type {
  Finding,
  SystemEntity,
  DesignDecision,
  TransformationPlan,
  ReductionThesis,
  AssistantStatus,
} from '@/shared/types/api';
import { RisksTab } from './RisksTab';
import { RoadmapTab } from './RoadmapTab';
import { DecisionsTab } from './DecisionsTab';
import { ThesisTab } from './ThesisTab';
import { AssistantTab } from './AssistantTab';
import { ProvenanceTab } from './ProvenanceTab';

export type DrawerTab =
  | 'assistant'
  | 'thesis'
  | 'risks'
  | 'roadmap'
  | 'decisions'
  | 'provenance'
  | null;

const TABS: {
  key: Exclude<DrawerTab, null>;
  label: string;
  subtitle: string;
  primary?: boolean;
}[] = [
  { key: 'assistant', label: 'Guide', subtitle: 'What next', primary: true },
  { key: 'thesis', label: 'Thesis', subtitle: 'Keep vs cut', primary: true },
  { key: 'risks', label: 'Risks', subtitle: 'What hurts' },
  { key: 'roadmap', label: 'Plan', subtitle: 'Waves' },
  { key: 'decisions', label: 'Log', subtitle: 'Decisions' },
  { key: 'provenance', label: 'From', subtitle: 'Source chain' },
];

export function DetailDrawer({
  domainId,
  tab,
  onTab,
  findings,
  systems,
  decisions,
  plan,
  thesis,
  thesisLoading,
  assistantStatus,
  assistantLoading,
  month,
  onScrub,
  onSpotlight,
  selectedSystem,
}: {
  /** Workspace-resolved domain id (seam S14 — replaces homepoc's DOMAIN_ID). */
  domainId: string;
  tab: DrawerTab;
  onTab: (t: DrawerTab) => void;
  findings: Finding[];
  systems: SystemEntity[];
  decisions: DesignDecision[];
  plan: TransformationPlan | undefined;
  thesis: ReductionThesis | undefined;
  thesisLoading: boolean;
  assistantStatus: AssistantStatus | undefined;
  assistantLoading: boolean;
  month: number;
  onScrub: (m: number) => void;
  onSpotlight: (systemIds: string[]) => void;
  selectedSystem?: SystemEntity | null;
}) {
  const open = tab !== null;

  return (
    <>
      {/* Right-edge tab rail */}
      <div className="pointer-events-auto absolute right-4 top-1/2 z-20 -translate-y-1/2">
        <div className="flex flex-col gap-1.5 rounded-2xl border border-border bg-surface-raised/60 p-2 shadow-2xl backdrop-blur-xl">
          {TABS.map((t) => {
            const active = tab === t.key;
            return (
              <button
                key={t.key}
                onClick={() => onTab(active ? null : t.key)}
                className={cn(
                  'group relative flex h-14 w-16 flex-col items-center justify-center rounded-xl text-center transition-all',
                  active
                    ? 'bg-gradient-to-br from-primary/40 to-primary/10 text-primary ring-1 ring-primary/60'
                    : t.primary
                    ? 'text-foreground-muted hover:bg-foreground/5 hover:text-foreground'
                    : 'text-foreground-subtle hover:bg-foreground/5 hover:text-foreground/80'
                )}
                aria-label={t.label}
              >
                <span
                  className={cn(
                    'text-[11px] font-bold uppercase tracking-widest',
                    t.primary && !active && 'text-foreground/80'
                  )}
                >
                  {t.label}
                </span>
                <span className="mt-0.5 text-[9px] text-foreground-subtle">{t.subtitle}</span>
                <span className="pointer-events-none absolute right-[72px] top-1/2 -translate-y-1/2 whitespace-nowrap rounded-md bg-surface-raised/95 px-2 py-1 text-[11px] font-medium text-foreground/80 opacity-0 shadow-lg ring-1 ring-border transition-opacity group-hover:opacity-100">
                  {active ? 'Collapse' : t.subtitle}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Drawer panel */}
      <AnimatePresence>
        {open && (
          <motion.aside
            initial={{ x: '100%', opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            exit={{ x: '100%', opacity: 0 }}
            transition={{ type: 'spring', damping: 28, stiffness: 280 }}
            className="pointer-events-auto absolute right-24 top-1/2 z-20 flex h-[min(82vh,680px)] w-[min(440px,calc(100vw-10rem))] -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-border bg-surface-raised/90 shadow-2xl backdrop-blur-2xl"
          >
            {tab === 'assistant' && (
              <AssistantTab
                status={assistantStatus}
                loading={assistantLoading}
                onGoToTab={onTab}
              />
            )}
            {tab === 'thesis' && (
              <ThesisTab
                thesis={thesis}
                loading={thesisLoading}
                onSpotlight={onSpotlight}
              />
            )}
            {tab === 'risks' && (
              <RisksTab findings={findings} systems={systems} onSpotlight={onSpotlight} />
            )}
            {tab === 'roadmap' && (
              <RoadmapTab plan={plan} month={month} onScrub={onScrub} />
            )}
            {tab === 'decisions' && <DecisionsTab domainId={domainId} decisions={decisions} />}
            {tab === 'provenance' && (
              <ProvenanceTab domainId={domainId} selectedSystem={selectedSystem ?? null} />
            )}
          </motion.aside>
        )}
      </AnimatePresence>
    </>
  );
}
