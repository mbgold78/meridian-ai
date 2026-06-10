import { useMemo } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Buildings, CaretLeft } from '@phosphor-icons/react';
import { Button } from '@/components/ui/button';
import {
  BEACON_COMPANY_NAME,
  CROSS_PORTFOLIO_OPPORTUNITIES,
  INDUSTRY_LABEL,
  LIFECYCLE_PHASE_LABEL,
  getSubsidiary,
} from '@/fixtures/beacon';
import type { BeaconSubsidiary } from '@/fixtures/beacon';
import { cn } from '@/lib/utils';
import { portfolioPaths } from './paths';
import { CapabilitiesTab } from './tabs/CapabilitiesTab';
import { EnvironmentTab } from './tabs/EnvironmentTab';
import { OperationsTab } from './tabs/OperationsTab';
import { PlanTab } from './tabs/PlanTab';

/**
 * Subsidiary COO view (ported from homepoc Beacon Stage 4 under the
 * product route namespace — chunk C9).
 *
 * The second vantage-point surface. Where the Corporate Operating
 * Partner sees the federation, the Subsidiary COO sees one operating
 * company AND the parent's signals reaching into it. The dual-context
 * is the point: this isn't a single-company operating dashboard, it's
 * "you're in Beacon's portfolio and here's what that means for you."
 *
 * Page composition (top to bottom):
 *   1. Subsidiary header strip — identity, industry, micro-stats.
 *   2. Portfolio context band — the "you sit inside Beacon" signal.
 *   3. Tab bar — Capabilities · Environment · Plan · Operations.
 *      URL search param `?tab=...` drives state; browser back/forward
 *      and direct linking work.
 *   4. Active tab content.
 *
 * Tab content lives in `tabs/` siblings to keep this file readable.
 */
export function SubsidiaryCooView() {
  const { slug = '' } = useParams();
  const subsidiary = getSubsidiary(slug);
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = parseTab(searchParams.get('tab'));

  if (!subsidiary) return <SubsidiaryNotFound slug={slug} />;

  const setTab = (next: TabId) => {
    const nextParams = new URLSearchParams(searchParams);
    nextParams.set('tab', next);
    setSearchParams(nextParams, { replace: false });
  };

  return (
    <div className="mx-auto max-w-[1200px]">
      <SubsidiaryHeader sub={subsidiary} />
      <PortfolioContextBand sub={subsidiary} />
      <TabBar active={activeTab} onChange={setTab} />
      <div className="px-8 pb-12 pt-8">
        {activeTab === 'capabilities' && <CapabilitiesTab sub={subsidiary} />}
        {activeTab === 'environment' && <EnvironmentTab sub={subsidiary} />}
        {activeTab === 'plan' && <PlanTab sub={subsidiary} />}
        {activeTab === 'operations' && <OperationsTab sub={subsidiary} />}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Tab plumbing                                                       */
/* ------------------------------------------------------------------ */

const TAB_IDS = ['capabilities', 'environment', 'plan', 'operations'] as const;
type TabId = (typeof TAB_IDS)[number];

const TAB_LABEL: Record<TabId, string> = {
  capabilities: 'Capabilities',
  environment: 'Environment',
  plan: 'Plan',
  operations: 'Operations',
};

function parseTab(raw: string | null): TabId {
  if (raw && (TAB_IDS as readonly string[]).includes(raw)) return raw as TabId;
  return 'capabilities';
}

interface TabBarProps {
  active: TabId;
  onChange: (next: TabId) => void;
}

function TabBar({ active, onChange }: TabBarProps) {
  return (
    <nav
      aria-label="Subsidiary view tabs"
      className="border-b border-border px-8"
    >
      <ul role="tablist" className="-mb-px flex gap-1 overflow-x-auto">
        {TAB_IDS.map((id) => {
          const isActive = id === active;
          return (
            <li key={id} role="presentation">
              <button
                type="button"
                role="tab"
                aria-selected={isActive}
                onClick={() => onChange(id)}
                className={cn(
                  'inline-flex h-12 items-center px-6 text-[14px] leading-[20px] transition-colors duration-100',
                  isActive
                    ? 'border-b-2 border-foreground font-semibold text-foreground'
                    : 'border-b-2 border-transparent font-medium text-foreground-muted hover:text-foreground',
                )}
              >
                {TAB_LABEL[id]}
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/* ------------------------------------------------------------------ */
/*  Zone 1 — Subsidiary header strip                                   */
/* ------------------------------------------------------------------ */

function SubsidiaryHeader({ sub }: { sub: BeaconSubsidiary }) {
  return (
    <header className="border-b border-border px-8 py-9">
      <div className="flex flex-col gap-8 md:flex-row md:items-end md:justify-between">
        <div className="md:max-w-[58%]">
          <p className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-subtle">
            {INDUSTRY_LABEL[sub.industry]} · {sub.industryDetail}
          </p>
          <h1 className="mt-2.5 font-serif text-[40px] font-semibold leading-[44px] -tracking-[0.015em] text-foreground">
            {sub.name}
          </h1>
          <p className="mt-3 max-w-[52ch] text-[15px] leading-[23px] text-foreground-muted">
            {sub.description}
          </p>
        </div>
        {/* Stat rail — a dossier-style hairline block, mono figures. */}
        <dl className="flex shrink-0 divide-x divide-border overflow-hidden rounded-md border border-border bg-surface">
          <RailStat label="Revenue" value={formatRevenue(sub.revenueMillions)} />
          <RailStat label="Headcount" value={formatHeadcount(sub.headcount)} />
          <RailStat label="HQ" value={sub.hqCity} />
        </dl>
      </div>
    </header>
  );
}

interface MicroStatProps {
  label: string;
  value: string;
}

function RailStat({ label, value }: MicroStatProps) {
  return (
    <div className="px-5 py-3.5">
      <dt className="text-[10px] font-medium uppercase tracking-[0.06em] text-foreground-subtle">
        {label}
      </dt>
      <dd
        className="mt-1.5 text-[19px] font-semibold leading-[24px] -tracking-[0.01em] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {value}
      </dd>
    </div>
  );
}

function formatRevenue(millions: number): string {
  return millions >= 1000 ? `$${(millions / 1000).toFixed(1)}B` : `$${millions}M`;
}

function formatHeadcount(n: number): string {
  return n.toLocaleString('en-US');
}

/* ------------------------------------------------------------------ */
/*  Zone 2 — Portfolio context band                                    */
/* ------------------------------------------------------------------ */

function PortfolioContextBand({ sub }: { sub: BeaconSubsidiary }) {
  const navigate = useNavigate();
  const opportunityCount = useMemo(
    () =>
      CROSS_PORTFOLIO_OPPORTUNITIES.filter((opp) =>
        opp.affectedSubsidiarySlugs.includes(sub.slug),
      ).length,
    [sub.slug],
  );

  // Federation invitations are fixture-driven (D2 §3.2). Each subsidiary
  // carries `pendingFederationInvitations`; the band reads it directly.
  // The invitation surfaced here is wired to the governance-3 federation
  // contract detail surface for Curasync and Polestar (the contract's
  // two parties). Other subsidiaries either have zero invitations or
  // have no fixture-bound federation contract; the segment renders as
  // inert text in that case. The slug→contract binding is fixture-only
  // and is replaced by the contract registry at the live swap.
  const federationInvitations = sub.pendingFederationInvitations;
  const federationContractId =
    sub.slug === 'curasync-clinical' || sub.slug === 'polestar-life'
      ? 'fi-shared-pre-fill-agent'
      : null;

  return (
    <div
      className="flex items-center gap-2 border-b border-border bg-surface px-8 py-4 text-[14px] leading-[20px]"
      aria-label="Portfolio context"
    >
      <Buildings
        size={16}
        weight="regular"
        aria-hidden
        className="shrink-0 text-foreground-muted"
      />
      <ul className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <BandSegment text={`Part of ${BEACON_COMPANY_NAME}`} />
        <BandSegment
          text={`Currently in ${LIFECYCLE_PHASE_LABEL[sub.lifecyclePhase]} phase`}
        />
        <BandSegment
          text={
            opportunityCount === 1
              ? '1 cross-portfolio opportunity involves you'
              : `${opportunityCount} cross-portfolio opportunities involve you`
          }
          count={opportunityCount}
        />
        <BandSegment
          text={
            federationInvitations === 1
              ? '1 federation invitation pending'
              : `${federationInvitations} federation invitations pending`
          }
          count={federationInvitations}
          onClick={
            federationContractId
              ? () =>
                  navigate(
                    `${portfolioPaths.contract(federationContractId)}?from=subsidiary:${sub.slug}`,
                  )
              : undefined
          }
        />
      </ul>
    </div>
  );
}

interface BandSegmentProps {
  text: string;
  /** When provided, treat as a (visually) interactive link segment. */
  count?: number;
  /** When provided alongside a non-zero count, the segment navigates on click. */
  onClick?: () => void;
}

function BandSegment({ text, count, onClick }: BandSegmentProps) {
  const isLink = typeof count === 'number' && count > 0;
  return (
    <li className="flex items-center gap-2">
      {isLink ? (
        <button
          type="button"
          onClick={onClick}
          className="text-foreground transition-colors duration-100 hover:text-primary"
        >
          {text}
        </button>
      ) : (
        <span className="text-foreground">{text}</span>
      )}
      <span aria-hidden className="text-foreground-subtle">
        ·
      </span>
    </li>
  );
}

/* ------------------------------------------------------------------ */
/*  Empty state — slug not in fixture                                  */
/* ------------------------------------------------------------------ */

function SubsidiaryNotFound({ slug }: { slug: string }) {
  return (
    <div className="mx-auto max-w-screen-md px-8 py-12">
      <div className="rounded-md border border-destructive/40 bg-destructive-surface p-8">
        <h1 className="text-[22px] font-semibold leading-[28px] text-destructive">
          Subsidiary not found
        </h1>
        <p className="mt-2 text-[14px] leading-[20px] text-foreground-muted">
          No Beacon subsidiary matches the slug "{slug}".
        </p>
        <Button asChild variant="outline" size="sm" className="mt-6">
          <Link to={portfolioPaths.root} className="gap-1">
            <CaretLeft size={16} aria-hidden />
            Back to vantage-point picker
          </Link>
        </Button>
      </div>
    </div>
  );
}
