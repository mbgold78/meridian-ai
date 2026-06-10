import { Outlet, Link, useLocation } from 'react-router-dom';

/*
 * A3 PORT SEAMS (restore in C7/C8 — port-map §7):
 *   import { PersonaSwitcher } from '@/features/personas/PersonaSwitcher';  // C8
 *   import { useTerminology } from '@/app/theme';                           // C7
 * Those modules are owned by later chunks and do not exist yet. When they
 * land: re-add <PersonaSwitcher /> at both SEAM markers below and replace the
 * literal 'Capability' with t('capability', 'singular') from useTerminology.
 *
 * Styling note: the slate-950 / meridian-400 legacy styling here is kept
 * verbatim on purpose — C8 owns the token-system migration for canvas
 * surfaces (port-map §2.5 step 7).
 */

export function CanvasLayout() {
  const location = useLocation();
  const onCanvas = location.pathname === '/';

  return (
    <div className="relative h-screen w-screen overflow-hidden bg-slate-950">
      {/* Floating brand + persona — only on canvas */}
      {onCanvas && (
        <>
          <div className="pointer-events-auto absolute left-5 top-5 z-30">
            <Link
              to="/"
              className="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-slate-950/60 px-3 py-2 shadow-xl backdrop-blur-xl"
            >
              <span className="h-2 w-2 rounded-full bg-meridian-400 shadow-[0_0_8px_#38bdf8]" />
              <span className="text-sm font-black tracking-[0.2em] text-white">MERIDIAN</span>
            </Link>
          </div>
          <div className="pointer-events-auto absolute right-5 top-5 z-30 flex items-center gap-2">
            <Link
              to="/designer/capabilities"
              className="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-slate-950/60 px-3 py-2 text-xs font-medium text-white/80 shadow-xl backdrop-blur-xl hover:text-white"
            >
              Capability Designer
            </Link>
            <Link
              to="/demo/beacon"
              className="inline-flex items-center gap-2 rounded-xl border border-amber-300/30 bg-slate-950/60 px-3 py-2 text-xs font-medium text-amber-200/80 shadow-xl backdrop-blur-xl hover:text-amber-100"
              title="Beacon Industries demo (mocked)"
            >
              Demo Mode
            </Link>
            {/* SEAM: <PersonaSwitcher /> (C8) */}
          </div>
        </>
      )}

      {/* Back-to-canvas nav on non-canvas routes */}
      {!onCanvas && (
        <nav className="absolute left-0 right-0 top-0 z-30 flex items-center justify-between border-b border-white/10 bg-slate-950/80 px-6 py-3 backdrop-blur-xl">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm font-semibold text-white/80 hover:text-white"
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
            {/* SEAM: <PersonaSwitcher /> (C8) */}
          </div>
        </nav>
      )}

      <main className={onCanvas ? 'h-full w-full' : 'h-full w-full overflow-auto bg-slate-950 pt-14'}>
        <Outlet />
      </main>
    </div>
  );
}
