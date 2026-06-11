import { lazy, Suspense } from 'react';
import { Navigate, useRoutes, type RouteObject } from 'react-router-dom';
import { CanvasLayout, LoadingSpinner, NotFound } from '@/shared/components';
import { PORTFOLIO_ROUTES } from '@/features/portfolio';

// Heavy surfaces are lazy-loaded so the canvas (xyflow/d3/elk) and the
// capability designer (react-arborist) each land in their own chunk.
const LivingCanvas = lazy(() =>
  import('@/features/canvas').then((m) => ({ default: m.LivingCanvas })),
);
const FutureStateDesigner = lazy(() =>
  import('@/features/designer').then((m) => ({ default: m.FutureStateDesigner })),
);
const CapabilityDesigner = lazy(() =>
  import('@/features/capability-designer').then((m) => ({ default: m.CapabilityDesigner })),
);

/**
 * Application route table (port-map §7 C8).
 *
 * Namespaces — kept in sync with the backend SPA fallback list in
 * app/.../infrastructure/web/SpaController.java (a path reachable here but
 * absent there 404s on hard refresh against the Boot-served bundle):
 *
 *   /                       Living Canvas (under CanvasLayout)
 *   /designer               Future State Designer (under CanvasLayout)
 *   /designer/capabilities  Capability Designer — full-screen, token-themed;
 *                           intentionally NOT nested under CanvasLayout so the
 *                           legacy slate chrome never mixes with it (homepoc parity)
 *   /portfolio/**           Beacon dashboards (C9 table, features/portfolio/routes.tsx)
 *   /ecosystem /diagnostics /roadmap /transformation /decisions
 *                           legacy deep-links → canvas drawer views (homepoc parity)
 */
const APP_ROUTES: RouteObject[] = [
  {
    element: <CanvasLayout />,
    children: [
      { path: '/', element: <LivingCanvas /> },
      { path: '/designer', element: <FutureStateDesigner /> },

      // Old routes deep-link into the canvas drawer (ported from homepoc App.tsx).
      { path: '/ecosystem', element: <Navigate to="/" replace /> },
      { path: '/diagnostics', element: <Navigate to="/?view=risks" replace /> },
      { path: '/roadmap', element: <Navigate to="/?view=roadmap" replace /> },
      { path: '/transformation', element: <Navigate to="/?view=roadmap" replace /> },
      { path: '/decisions', element: <Navigate to="/?view=decisions" replace /> },
    ],
  },
  { path: '/designer/capabilities', element: <CapabilityDesigner /> },
  ...PORTFOLIO_ROUTES,
  // Catch-all: SpaController forwards unknown paths to index.html, so an
  // unmatched route would otherwise render a silent blank page.
  { path: '*', element: <NotFound /> },
];

function App() {
  const element = useRoutes(APP_ROUTES);
  return (
    <Suspense fallback={<LoadingSpinner size="lg" className="h-screen" />}>
      {element}
    </Suspense>
  );
}

export default App;
