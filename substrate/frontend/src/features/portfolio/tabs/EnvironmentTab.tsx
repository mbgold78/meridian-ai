import { useMemo } from 'react';
import {
  Buildings,
  ChartLine,
  ChatsCircle,
  Database,
  Plugs,
  ShieldCheck,
  Stack,
  Warning,
} from '@phosphor-icons/react';
import type { Industry } from '@/fixtures/beacon';
import type { BeaconSubsidiary, BeaconSystem, SystemKind } from '@/fixtures/beacon';
import { cn } from '@/lib/utils';

/**
 * Environment tab — systems, integrations, and where the friction is.
 *
 * The grid sources every card from the subsidiary's `systems` fixture.
 * One card carries a Meridian-flagged warning state; the heuristic
 * picks the system whose backing process has the highest tribal-
 * knowledge concentration, since that's the natural place a federated
 * platform would surface concern. If no such process exists, the
 * first system in the fixture is flagged.
 *
 * External integration partners are stub-derived per industry — the
 * subsidiary fixture doesn't carry them today, and Stage 4 documents
 * the extension proposal in the completion report.
 */
export function EnvironmentTab({ sub }: { sub: BeaconSubsidiary }) {
  const flaggedId = useMemo(() => pickFlaggedSystemId(sub), [sub]);
  const partners = INDUSTRY_INTEGRATION_PARTNERS[sub.industry];

  return (
    <section aria-label="Environment" className="flex flex-col gap-12">
      <div>
        <header className="mb-6">
          <h2 className="text-[22px] font-semibold leading-[28px] -tracking-[0.005em] text-foreground">
            Systems and integrations
          </h2>
          <p className="mt-1 text-[14px] leading-[20px] text-foreground-muted">
            What's running, what's connected, where the friction is.
          </p>
        </header>
        <div className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
          {sub.systems.map((sys) => (
            <SystemCard
              key={sys.id}
              sys={sys}
              integrationCount={countIntegrations(sys, sub)}
              flagged={sys.id === flaggedId}
              flaggedReason={
                sys.id === flaggedId ? buildFlagReason(sys, sub) : undefined
              }
            />
          ))}
        </div>
      </div>

      <div>
        <header className="mb-4">
          <h3 className="text-[18px] font-semibold leading-[24px] text-foreground">
            External integration partners
          </h3>
          <p className="mt-1 text-[14px] leading-[20px] text-foreground-muted">
            Third parties this subsidiary depends on for data exchange.
          </p>
        </header>
        <ul className="flex flex-wrap gap-2">
          {partners.map((name) => (
            <li key={name}>
              <span className="inline-flex items-center rounded-md border border-border bg-surface-raised px-3 py-2 text-[13px] leading-[18px] text-foreground">
                {name}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  System card                                                        */
/* ------------------------------------------------------------------ */

interface SystemCardProps {
  sys: BeaconSystem;
  integrationCount: number;
  flagged: boolean;
  flaggedReason?: string;
}

function SystemCard({ sys, integrationCount, flagged, flaggedReason }: SystemCardProps) {
  const Icon = SYSTEM_KIND_ICON[sys.kind];
  return (
    <article
      className={cn(
        'flex flex-col rounded-md border bg-surface p-5 transition-colors duration-100',
        flagged
          ? 'border-warning/40 bg-warning-surface/40'
          : 'border-border hover:border-border-strong',
      )}
    >
      <header className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <span
            className={cn(
              'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
              flagged ? 'bg-warning/10 text-warning' : 'bg-surface-raised text-foreground-muted',
            )}
          >
            <Icon size={20} weight="regular" aria-hidden />
          </span>
          <div className="min-w-0">
            <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
              {SYSTEM_KIND_LABEL[sys.kind]}
            </p>
            <h4 className="mt-0.5 text-[18px] font-semibold leading-[24px] text-foreground">
              {sys.name}
            </h4>
          </div>
        </div>
        {flagged && (
          <Warning
            size={18}
            weight="regular"
            aria-hidden
            className="shrink-0 text-warning"
          />
        )}
      </header>
      {sys.note && (
        <p className="mt-3 text-[13px] leading-[18px] text-foreground-muted">
          {sys.note}
        </p>
      )}
      {sys.vendor && (
        <p className="mt-2 text-[13px] leading-[18px] text-foreground-muted">
          Vendor:{' '}
          <span className="text-foreground">{sys.vendor}</span>
        </p>
      )}
      <p className="mt-3 text-[13px] leading-[18px] text-foreground-muted">
        Connected to{' '}
        <span
          className="font-medium text-foreground"
          style={{ fontFeatureSettings: '"tnum"' }}
        >
          {integrationCount}
        </span>{' '}
        process{integrationCount === 1 ? '' : 'es'}
      </p>
      {flagged && flaggedReason && (
        <p className="mt-4 border-t border-warning/30 pt-4 text-[13px] leading-[18px] text-foreground">
          <span className="font-medium text-warning">Flagged by Meridian:</span>{' '}
          {flaggedReason}
        </p>
      )}
    </article>
  );
}

/* ------------------------------------------------------------------ */
/*  Helpers + per-industry data                                        */
/* ------------------------------------------------------------------ */

const SYSTEM_KIND_ICON: Record<SystemKind, typeof Database> = {
  erp: Buildings,
  lob: Stack,
  analytics: ChartLine,
  collaboration: ChatsCircle,
  identity: ShieldCheck,
  custom: Plugs,
};

const SYSTEM_KIND_LABEL: Record<SystemKind, string> = {
  erp: 'ERP',
  lob: 'Line of business',
  analytics: 'Analytics',
  collaboration: 'Collaboration',
  identity: 'Identity',
  custom: 'Custom integration',
};

function countIntegrations(sys: BeaconSystem, sub: BeaconSubsidiary): number {
  return sub.processes.filter((p) => p.systemIds.includes(sys.id)).length;
}

/**
 * Pick a system to flag with a Meridian warning. Prefers a system that
 * backs a high-tribal-knowledge process (the natural concern surface);
 * falls back to the first system in the fixture for visual consistency.
 */
function pickFlaggedSystemId(sub: BeaconSubsidiary): string | null {
  if (sub.systems.length === 0) return null;
  const tribalProc = sub.processes.find((p) => p.tribalKnowledge === 'high');
  if (tribalProc) {
    const sysId = tribalProc.systemIds[0];
    if (sysId && sub.systems.some((s) => s.id === sysId)) return sysId;
  }
  return sub.systems[0].id;
}

function buildFlagReason(sys: BeaconSystem, sub: BeaconSubsidiary): string {
  const tribalProc = sub.processes.find(
    (p) => p.tribalKnowledge === 'high' && p.systemIds.includes(sys.id),
  );
  if (tribalProc) {
    return `Process "${tribalProc.name}" depends on tribal knowledge concentrated in a small number of staff. Federated playbook proposed.`;
  }
  // Generic stand-in when no tribal-knowledge signal exists in fixture.
  return `Integration drift detected between ${sys.name} and at least one downstream system. Review proposed.`;
}

/**
 * Stub-derivation source. Each industry maps to a small list of
 * plausible external integration partners shown as canonical chips on
 * the Environment tab. Documented in the Stage 4 completion report as
 * a fixture-extension proposal: the subsidiary fixture itself should
 * eventually carry per-subsidiary `integrationPartners` rather than
 * inheriting from this industry-wide table.
 */
const INDUSTRY_INTEGRATION_PARTNERS: Record<Industry, string[]> = {
  healthcare: [
    'Athenahealth',
    'Surescripts',
    'Change Healthcare',
    'Availity',
    'Twilio',
  ],
  government: [
    'ContractorCloud',
    'Granicus',
    'Tyler Technologies',
    'OpenGov',
  ],
  insurance: [
    'Verisk',
    'LexisNexis Risk',
    'Guidewire Marketplace',
    'Duck Creek',
  ],
  instrumentation: [
    'Roche Middleware',
    'Sysmex Connect',
    'PTC ThingWorx',
    'AWS IoT',
  ],
  financial: [
    'Plaid',
    'Stripe Treasury',
    'FIS Connect',
    'ACI Worldwide',
  ],
  legal: [
    'MatterStack',
    'NetDocuments',
    'iManage',
    'Clio Connect',
  ],
  logistics: [
    'project44',
    'FourKites',
    'EDI VAN (TrueCommerce)',
    'Geotab',
  ],
  education: [
    'CardWave',
    'Ellucian',
    'Workday Student',
    'Anthology',
  ],
  creative: [
    'Adobe Cloud APIs',
    'Figma Connect',
    'Frame.io',
    'Webflow Logic',
  ],
};
