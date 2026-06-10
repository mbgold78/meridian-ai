/**
 * Route paths for the portfolio feature — the product (non-/demo) route
 * namespace the Beacon dashboards live under (port-map §7 C9).
 *
 * homepoc served these surfaces under `/demo/beacon/*`; substrate mounts
 * them under `/portfolio/*`. Every internal link goes through these
 * builders so the namespace lives in exactly one file.
 */

export const PORTFOLIO_ROOT = '/portfolio';

export const portfolioPaths = {
  /** Landing page — vantage-point picker (Operating Partner vs Subsidiary COO). */
  root: PORTFOLIO_ROOT,
  /** Corporate Operating Partner dashboard (homepoc: /demo/beacon/portfolio). */
  dashboard: `${PORTFOLIO_ROOT}/dashboard`,
  /** Subsidiary COO view; optional tab is carried as a `?tab=` search param. */
  subsidiary: (slug: string) => `${PORTFOLIO_ROOT}/subsidiary/${slug}`,
  /** Cascade detail surface. */
  cascade: (cascadeId: string) => `${PORTFOLIO_ROOT}/cascade/${cascadeId}`,
  /** Coordination contract detail surface. */
  contract: (contractId: string) => `${PORTFOLIO_ROOT}/contract/${contractId}`,
} as const;
