import { Outlet, Link, useLocation } from 'react-router-dom';
import { PersonaSwitcher } from '@/features/personas/PersonaSwitcher';
import { useTerminology, useTheme } from '@/app/theme';

/*
 * Chrome shell for the canvas-family surfaces (Living Canvas at '/', Future
 * State Designer at '/designer'). A3 port seams restored in C8: the
 * PersonaSwitcher mounts at both nav positions and the capability link label
 * resolves through workspace terminology. Styling migrated from the legacy
 * slate-950/meridian literals onto the token system (port-map §2.5 step 7);
 * the brand wordmark reads BrandingConfig.appDisplayName, matching the
 * WelcomeOverlay idiom.
 */

export function CanvasLayout() {
  const location = useLocation();
  const { t } = useTerminology();
  const { config } = useTheme();
  const onCanvas = location.pathname === '/';

  return (
    <div className="relative h-screen w-screen overflow-hidden bg-background">
      {/* Floating brand + persona — only on canvas */}
      {onCanvas && (
        <>
          <div className="pointer-events-auto absolute left-5 top-5 z-30">
            <Link
              to="/"
              className="inline-flex items-center gap-2 rounded-xl border border-border bg-surface/80 px-3 py-2 shadow-xl backdrop-blur-xl"
            >
              <span className="h-2 w-2 rounded-full bg-primary shadow-[0_0_8px_hsl(var(--primary))]" />
              <span className="text-sm font-black uppercase tracking-[0.2em] text-foreground">
                {config.appDisplayName}
              </span>
            </Link>
          </div>
          <div className="pointer-events-auto absolute right-5 top-5 z-30 flex items-center gap-2">
            <Link
              to="/designer/capabilities"
              className="inline-flex items-center gap-2 rounded-xl border border-border bg-surface/80 px-3 py-2 text-xs font-medium text-foreground-muted shadow-xl backdrop-blur-xl hover:text-foreground"
            >
              {t('capability', 'singular')} Designer
            </Link>
            {/* homepoc linked /demo/beacon; the dashboards live at the product
                namespace /portfolio in substrate (features/portfolio/paths.ts). */}
            <Link
              to="/portfolio"
              className="inline-flex items-center gap-2 rounded-xl border border-warning/40 bg-surface/80 px-3 py-2 text-xs font-medium text-warning shadow-xl backdrop-blur-xl hover:text-warning-foreground"
              // "(demo)" not "(fixture data)": the dashboards render LIVE
              // engine slices whenever the backend answers (FIX-03) — the
              // PortfolioLayout banner carries the precise provenance.
              title="Beacon Industries portfolio (demo)"
            >
              Portfolio
            </Link>
            <PersonaSwitcher />
          </div>
        </>
      )}

      {/* Back-to-canvas nav on non-canvas routes */}
      {!onCanvas && (
        <nav className="absolute left-0 right-0 top-0 z-30 flex items-center justify-between border-b border-border bg-surface/80 px-6 py-3 backdrop-blur-xl">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm font-semibold text-foreground-muted hover:text-foreground"
          >
            <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
              <path
                fillRule="evenodd"
                d="M9.707 14.707a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414l4-4a1 1 0 011.414 1.414L7.414 9H15a1 1 0 110 2H7.414l2.293 2.293a1 1 0 010 1.414z"
                clipRule="evenodd"
              />
            </svg>
            Back to canvas
          </Link>
          <div className="flex items-center gap-3">
            <PersonaSwitcher />
          </div>
        </nav>
      )}

      <main className={onCanvas ? 'h-full w-full' : 'h-full w-full overflow-auto bg-background pt-14'}>
        <Outlet />
      </main>
    </div>
  );
}
