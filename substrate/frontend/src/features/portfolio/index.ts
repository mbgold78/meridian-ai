/**
 * Portfolio feature — the four Beacon dashboards ported from homepoc
 * under the product route namespace (port-map §7 C9):
 *
 *   1. CorporateOperatingPartnerView — federated 5-zone dashboard + cascade grid
 *   2. SubsidiaryCooView — single-subsidiary slice with 4 tabs
 *   3. CascadeDetailView — level-by-level cascade timeline
 *   4. ContractDetailView — coordination contract terms surface
 *
 * Plus the landing/vantage-point picker and the persistent shell.
 *
 * Data source: TEMPORARILY the Beacon fixtures behind the single
 * `BEACON_PORTFOLIO` swap point in `@/fixtures/beacon` (port-map §5 S5).
 * Phases 2–4 replace the fixture factory with React Query hooks +
 * adapters from substrate DTOs; the components themselves don't change
 * at swap time.
 */
export { PORTFOLIO_ROUTES } from './routes';
export { portfolioPaths, PORTFOLIO_ROOT } from './paths';
export { PortfolioLayout } from './PortfolioLayout';
export { PortfolioLandingPage } from './PortfolioLandingPage';
export { CorporateOperatingPartnerView } from './CorporateOperatingPartnerView';
export { SubsidiaryCooView } from './SubsidiaryCooView';
export { CascadeDetailView } from './CascadeDetailView';
export { ContractDetailView } from './ContractDetailView';
export { setPortfolioClock, todayIso, FIXTURE_CLOCK } from './clock';
export type { PortfolioClock } from './clock';
