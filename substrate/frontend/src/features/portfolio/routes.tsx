import type { RouteObject } from 'react-router-dom';
import { NotFound } from '@/shared/components';
import { PortfolioLayout } from './PortfolioLayout';
import { PortfolioLandingPage } from './PortfolioLandingPage';
import { CorporateOperatingPartnerView } from './CorporateOperatingPartnerView';
import { SubsidiaryCooView } from './SubsidiaryCooView';
import { CascadeDetailView } from './CascadeDetailView';
import { ContractDetailView } from './ContractDetailView';

/**
 * Route table for the portfolio feature — the four Beacon dashboards
 * under the product route namespace `/portfolio` (port-map §7 C9).
 *
 * Chunk C8 owns App.tsx/main.tsx and mounts this table into the app
 * router. Works with both router styles:
 *   - `createBrowserRouter([...otherRoutes, ...PORTFOLIO_ROUTES])`
 *   - `useRoutes([...otherRoutes, ...PORTFOLIO_ROUTES])`
 *
 * Route map (paths centralized in ./paths.ts):
 *   /portfolio                        → landing (vantage-point picker)
 *   /portfolio/dashboard              → Corporate Operating Partner
 *   /portfolio/subsidiary/:slug       → Subsidiary COO (+ ?tab= search param)
 *   /portfolio/cascade/:cascadeId     → cascade detail
 *   /portfolio/contract/:contractId   → contract detail
 */
export const PORTFOLIO_ROUTES: RouteObject[] = [
  {
    path: '/portfolio',
    element: <PortfolioLayout />,
    children: [
      { index: true, element: <PortfolioLandingPage /> },
      { path: 'dashboard', element: <CorporateOperatingPartnerView /> },
      { path: 'subsidiary/:slug', element: <SubsidiaryCooView /> },
      { path: 'cascade/:cascadeId', element: <CascadeDetailView /> },
      { path: 'contract/:contractId', element: <ContractDetailView /> },
      // Unmatched /portfolio/** would otherwise fail the whole route tree
      // and blank the page (e.g. a stale tab deep link with an extra segment).
      { path: '*', element: <NotFound /> },
    ],
  },
];
