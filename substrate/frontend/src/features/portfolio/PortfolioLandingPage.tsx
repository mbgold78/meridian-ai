import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, Buildings, Network } from '@phosphor-icons/react';
import { Button } from '@/components/ui/button';
import {
  BEACON_COMPANY_NAME,
  BEACON_PORTFOLIO,
  BEACON_SUBSIDIARIES,
  INDUSTRY_LABEL,
} from '@/fixtures/beacon';
import { portfolioPaths } from './paths';

const REVENUE_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 1,
});

/**
 * Portfolio landing page — vantage-point picker (ported from homepoc's
 * DemoLandingPage under the product route namespace).
 *
 * Two-card picker. The Subsidiary COO card carries an inline subsidiary
 * selector that becomes the destination slug. A secondary text link below
 * the grid handles the exit-to-standard-product affordance at appropriate
 * weight.
 */
export function PortfolioLandingPage() {
  const navigate = useNavigate();
  const [subsidiarySlug, setSubsidiarySlug] = useState<string>(
    BEACON_SUBSIDIARIES[0].slug,
  );

  const totalRevenueLabel = REVENUE_FORMATTER.format(
    BEACON_PORTFOLIO.totalRevenueMillions / 1000,
  ).replace('US$', '$');

  return (
    <div className="mx-auto max-w-screen-xl px-6 py-12">
      <header className="mb-10 max-w-3xl">
        <p className="text-xs font-semibold uppercase tracking-[0.05em] text-foreground-muted">
          Beacon Industries · Fixture dataset
        </p>
        <h1 className="mt-2 font-serif text-[48px] font-semibold leading-[52px] -tracking-[0.02em]">
          {BEACON_COMPANY_NAME} runs Meridian across {BEACON_SUBSIDIARIES.length}{' '}
          subsidiaries.
        </h1>
        <p className="mt-4 text-base text-foreground-muted">
          A diversified portfolio operator headquartered in Atlanta, with{' '}
          {totalRevenueLabel}B in annual revenue across{' '}
          {Object.keys(INDUSTRY_LABEL).length} industries. Pick a vantage point
          to step into.
        </p>
      </header>

      <div className="grid gap-5 md:grid-cols-2">
        <PersonaCard
          icon={<Network size={24} aria-hidden />}
          eyebrow="Federated view"
          title="Corporate Operating Partner"
          body="See the portfolio across all 22 subsidiaries: lifecycle phase per sub, cross-portfolio AI opportunities, coordination signals."
          actionLabel="Enter portfolio dashboard"
          tone="primary"
          onAction={() => navigate(portfolioPaths.dashboard)}
        />

        <PersonaCard
          icon={<Buildings size={24} aria-hidden />}
          eyebrow="Single-subsidiary slice"
          title="Subsidiary COO"
          body="Step into one operating company. See its capability model, environment, plan, and operations — the same surfaces a subsidiary COO uses day-to-day."
          actionLabel="Enter subsidiary view"
          tone="default"
          onAction={() => navigate(portfolioPaths.subsidiary(subsidiarySlug))}
          slot={
            <label className="block text-xs font-medium text-foreground-muted">
              <span className="mb-1 block">Choose subsidiary</span>
              <select
                value={subsidiarySlug}
                onChange={(e) => setSubsidiarySlug(e.target.value)}
                className="block w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                aria-label="Choose subsidiary"
              >
                {BEACON_SUBSIDIARIES.map((s) => (
                  <option key={s.slug} value={s.slug}>
                    {s.name} — {INDUSTRY_LABEL[s.industry]}
                  </option>
                ))}
              </select>
            </label>
          }
        />
      </div>

      <p className="mt-6 text-sm">
        <button
          type="button"
          onClick={() => navigate('/')}
          className="text-foreground-muted transition-colors duration-100 hover:text-primary"
        >
          Exit and explore the standard product.
        </button>
      </p>

      <p className="mt-10 text-xs text-foreground-muted">
        Beacon Industries is a fictional demonstration portfolio. Cascades,
        contracts, agents, and portfolio statistics are served live by the
        substrate engines from seeded demo data; remaining surfaces still
        render fixture data while their live swaps land.
      </p>
    </div>
  );
}

interface PersonaCardProps {
  icon: React.ReactNode;
  eyebrow: string;
  title: string;
  body: string;
  actionLabel: string;
  tone: 'primary' | 'default' | 'muted';
  onAction: () => void;
  slot?: React.ReactNode;
}

function PersonaCard({
  icon,
  eyebrow,
  title,
  body,
  actionLabel,
  tone,
  onAction,
  slot,
}: PersonaCardProps) {
  const iconTone =
    tone === 'primary'
      ? 'bg-primary/10 text-primary'
      : tone === 'muted'
        ? 'bg-muted text-foreground-muted'
        : 'bg-secondary text-secondary-foreground';
  const buttonVariant =
    tone === 'primary' ? 'default' : tone === 'muted' ? 'ghost' : 'outline';

  return (
    <div className="flex h-full flex-col gap-4 rounded-md border border-border bg-surface-raised p-6 transition-colors duration-100 hover:border-border-strong">
      <div className="flex items-center gap-3">
        <span className={`inline-flex h-10 w-10 items-center justify-center rounded-md ${iconTone}`}>
          {icon}
        </span>
        <div className="flex flex-col">
          <span className="text-[11px] font-semibold uppercase tracking-[0.05em] text-foreground-muted">
            {eyebrow}
          </span>
          <h2 className="text-base font-semibold leading-tight">{title}</h2>
        </div>
      </div>

      <p className="text-sm leading-relaxed text-foreground-muted">{body}</p>

      {slot && <div>{slot}</div>}

      <div className="mt-auto pt-2">
        <Button
          variant={buttonVariant as 'default' | 'outline' | 'ghost'}
          className="w-full justify-between"
          onClick={onAction}
        >
          <span>{actionLabel}</span>
          <ArrowRight size={16} aria-hidden />
        </Button>
      </div>
    </div>
  );
}
