import { useEffect } from 'react';
import { Link, Outlet, useLocation, useParams } from 'react-router-dom';
import { CaretLeft, SquaresFour, X } from '@phosphor-icons/react';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { useTheme } from '@/app/theme';
import { BEACON_COMPANY_NAME, getSubsidiary } from '@/fixtures/beacon';
import { useBeaconPortfolioStats } from './portfolioData';
import { portfolioPaths } from './paths';

/**
 * Persistent shell for every /portfolio/* route (ported from homepoc's
 * DemoLayout under the product route namespace — port-map §7 C9).
 *
 * Two-row header:
 *   - Top banner identifies the dataset's PROVENANCE honestly. The original
 *     plan ("the banner comes out together with the fixtures at swap time")
 *     assumed an all-at-once swap; the swap actually landed slice-by-slice
 *     (cascades/contracts/agents/stats render live whenever the backend
 *     answers, fixtures stay as the degraded-mode fallback) and no slice
 *     owned the banner — so the static "Fixture dataset" claim mislabeled
 *     LIVE engine rows as fictional. The banner now derives from the same
 *     swap switch the dashboards use (`useBeaconPortfolioStats().source`;
 *     react-query dedupes the fetch with the dashboard's own query):
 *     backend answering ⇒ "live engine data + illustrative fixtures";
 *     degraded/loading ⇒ the original fixture claim.
 *   - Sub-row shows where you are inside the portfolio surfaces + offers
 *     a quick jump back to the vantage-point picker.
 *
 * The body is a plain Outlet — child routes own their own layout.
 */
export function PortfolioLayout() {
  const location = useLocation();
  const params = useParams();
  const stats = useBeaconPortfolioStats();

  // The Beacon dashboards ship dark-only (visual-foundation:
  // dark-mode-primary). Force dark while inside them and restore the
  // prior preference on exit so the rest of the product is untouched.
  const { preference, setPreference } = useTheme();
  useEffect(() => {
    const prev = preference;
    setPreference('dark');
    return () => setPreference(prev);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onLanding = location.pathname === portfolioPaths.root;
  const subsidiary = params.slug ? getSubsidiary(params.slug) : undefined;

  let viewLabel: string;
  if (onLanding) {
    viewLabel = 'Choose your vantage point';
  } else if (location.pathname === portfolioPaths.dashboard) {
    viewLabel = 'Corporate Operating Partner';
  } else if (subsidiary) {
    viewLabel = `Subsidiary COO — ${subsidiary.name}`;
  } else {
    viewLabel = 'Portfolio';
  }

  return (
    <div className="flex h-screen w-screen flex-col bg-background text-foreground">
      <div className="border-b border-amber-300/60 bg-amber-50 text-amber-900 dark:border-amber-700/60 dark:bg-amber-950/60 dark:text-amber-100">
        <div className="mx-auto flex max-w-screen-2xl items-center justify-between px-6 py-2 text-xs font-medium tracking-wide">
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-amber-900 dark:bg-amber-200" aria-hidden />
            <span data-banner-provenance={stats.source}>
              {stats.source === 'live'
                ? `${BEACON_COMPANY_NAME} — Demo workspace · live engine data + illustrative fixtures`
                : `${BEACON_COMPANY_NAME} — Fixture dataset (demo)`}
            </span>
          </div>
          <Button asChild variant="ghost" size="sm" className="h-7 gap-1 px-2 text-amber-900 hover:bg-amber-100 hover:text-amber-950 dark:text-amber-100 dark:hover:bg-amber-900/60 dark:hover:text-amber-50">
            <Link to="/" aria-label="Leave the portfolio surfaces and return to the product home">
              <X size={14} aria-hidden />
              <span>Exit</span>
            </Link>
          </Button>
        </div>
      </div>

      <header className="border-b border-border bg-card">
        <div className="mx-auto flex max-w-screen-2xl items-center gap-4 px-6 py-3">
          <Link
            to={portfolioPaths.root}
            className="inline-flex items-center gap-2 rounded-md px-2 py-1 text-sm font-semibold tracking-tight hover:bg-accent"
          >
            <SquaresFour size={16} aria-hidden />
            <span>Beacon Industries</span>
          </Link>
          <Separator orientation="vertical" className="h-6" />
          <span className="text-sm text-muted-foreground">{viewLabel}</span>

          <div className="ml-auto flex items-center gap-2">
            {!onLanding && (
              <Button asChild variant="outline" size="sm">
                <Link to={portfolioPaths.root} className="gap-1">
                  <CaretLeft size={16} aria-hidden />
                  Switch vantage point
                </Link>
              </Button>
            )}
          </div>
        </div>
      </header>

      <main className="flex-1 min-h-0 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
