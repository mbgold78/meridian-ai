import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, RouterProvider, createMemoryRouter } from 'react-router-dom';
import { PORTFOLIO_ROUTES } from '@/features/portfolio';
import { PortfolioLandingPage } from '@/features/portfolio/PortfolioLandingPage';
import { CorporateOperatingPartnerView } from '@/features/portfolio/CorporateOperatingPartnerView';
import { getSubsidiary } from '@/fixtures/beacon';

/**
 * Render tests for the four Beacon dashboards (chunk C9 acceptance:
 * "4 dashboards render"). Routed views are mounted through the real
 * PORTFOLIO_ROUTES table via a memory router so path params, search
 * params, and the layout shell are exercised exactly as C8's App
 * router will exercise them.
 *
 * useTheme is stubbed: the layout forces dark mode through it, and the
 * real ThemeProvider needs the branding API (C7's concern, not ours).
 */

const setPreferenceSpy = vi.hoisted(() => vi.fn());

vi.mock('@/app/theme', () => ({
  useTheme: () => ({
    preference: 'system',
    mode: 'dark',
    setPreference: setPreferenceSpy,
    t: (key: string) => key,
    config: {},
  }),
}));

function renderRoute(initialPath: string) {
  const router = createMemoryRouter(PORTFOLIO_ROUTES, {
    initialEntries: [initialPath],
  });
  return render(<RouterProvider router={router} />);
}

describe('PortfolioLandingPage', () => {
  it('renders the vantage-point picker with both persona cards', () => {
    render(
      <MemoryRouter>
        <PortfolioLandingPage />
      </MemoryRouter>,
    );
    expect(
      screen.getByRole('heading', { level: 1, name: /runs Meridian across 22/i }),
    ).toBeInTheDocument();
    expect(screen.getByText('Corporate Operating Partner')).toBeInTheDocument();
    expect(screen.getByText('Subsidiary COO')).toBeInTheDocument();
    expect(screen.getByLabelText('Choose subsidiary')).toBeInTheDocument();
  });
});

describe('CorporateOperatingPartnerView (dashboard 1)', () => {
  it('renders all five zones plus the issued-cascades grid', () => {
    render(
      <MemoryRouter>
        <CorporateOperatingPartnerView />
      </MemoryRouter>,
    );
    // Zone 1 — header strip
    expect(
      screen.getByRole('heading', { level: 1, name: 'Beacon Industries Portfolio' }),
    ).toBeInTheDocument();
    // Zone 2 — coordination signal strip
    expect(
      screen.getByRole('region', { name: 'Coordination signals' }),
    ).toBeInTheDocument();
    // Zone 3 — lifecycle phase columns
    expect(screen.getByText('Where each subsidiary sits')).toBeInTheDocument();
    // Zone 4 — cross-portfolio opportunities
    expect(screen.getByText('Where coordination is failing')).toBeInTheDocument();
    // Zone 4.5 — cascades grid
    expect(screen.getByText("Cascades you've issued")).toBeInTheDocument();
    expect(screen.getAllByText('Inspect cascade').length).toBeGreaterThanOrEqual(3);
    // Zone 5 — roster
    expect(screen.getByText('Full portfolio')).toBeInTheDocument();
    const roster = screen.getByRole('region', { name: 'Full subsidiary roster' });
    expect(within(roster).getAllByRole('row').length).toBeGreaterThan(22);
  });

  it('links subsidiaries into the product route namespace', () => {
    render(
      <MemoryRouter>
        <CorporateOperatingPartnerView />
      </MemoryRouter>,
    );
    const roster = screen.getByRole('region', { name: 'Full subsidiary roster' });
    const link = within(roster).getByRole('link', { name: 'Curasync Clinical Systems' });
    expect(link).toHaveAttribute('href', '/portfolio/subsidiary/curasync-clinical');
  });
});

describe('SubsidiaryCooView (dashboard 2)', () => {
  it('renders header, portfolio context band, and the default capabilities tab', () => {
    renderRoute('/portfolio/subsidiary/curasync-clinical');
    expect(
      screen.getByRole('heading', { level: 1, name: 'Curasync Clinical Systems' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Part of Beacon Industries')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Capabilities', selected: true })).toBeInTheDocument();
    expect(screen.getByRole('tree')).toBeInTheDocument();
  });

  it('drives the active tab from the ?tab= search param', () => {
    renderRoute('/portfolio/subsidiary/curasync-clinical?tab=operations');
    expect(screen.getByRole('tab', { name: 'Operations', selected: true })).toBeInTheDocument();
    expect(screen.getByText('Operating today')).toBeInTheDocument();
    // Deterministic clock: "surfaced N days ago" labels render without NaN.
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });

  it('renders the environment and plan tabs', () => {
    renderRoute('/portfolio/subsidiary/curasync-clinical?tab=environment');
    expect(screen.getByText('Systems and integrations')).toBeInTheDocument();

    renderRoute('/portfolio/subsidiary/curasync-clinical?tab=plan');
    expect(screen.getByText('Initiatives in flight')).toBeInTheDocument();
    expect(
      screen.getByRole('list', { name: 'Lifecycle progression' }),
    ).toBeInTheDocument();
  });

  it('renders a not-found panel for unknown slugs', () => {
    renderRoute('/portfolio/subsidiary/not-a-company');
    expect(screen.getByText('Subsidiary not found')).toBeInTheDocument();
  });
});

describe('CascadeDetailView (dashboard 3)', () => {
  it('renders the durable original critique and the level timeline', () => {
    const cascadePath = '/portfolio/cascade/c-vendor-cost';
    renderRoute(cascadePath);
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: /Vendor costs across healthcare subsidiaries grew 14% YoY/,
      }),
    ).toBeInTheDocument();
    expect(screen.getByText('Acceptance criteria')).toBeInTheDocument();
    expect(screen.getByText('Level-by-level transparency')).toBeInTheDocument();
    // Bottom level is a documented Decline — legitimate resolution, not error.
    expect(screen.getByText('Declined')).toBeInTheDocument();
    expect(screen.getByText(/Vendor contract 4127/)).toBeInTheDocument();
    expect(screen.getByText(/Currently at L2, state: declined\./)).toBeInTheDocument();
  });

  it('renders a not-found panel for unknown cascade ids', () => {
    renderRoute('/portfolio/cascade/c-nope');
    expect(screen.getByText('Cascade not found')).toBeInTheDocument();
  });
});

describe('ContractDetailView (dashboard 4)', () => {
  it('renders parties, commitments, enforcement, and inert Review/Decline affordances', () => {
    renderRoute('/portfolio/contract/co-tribal-knowledge-depopulation');
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: /Tribal-knowledge depopulation across four/,
      }),
    ).toBeInTheDocument();
    expect(screen.getByText('Parties and commitments')).toBeInTheDocument();
    expect(screen.getByText('Beacon Corporate Operations')).toBeInTheDocument();
    expect(screen.getByText('Enforcement')).toBeInTheDocument();
    expect(screen.getByText(/co-tribal-knowledge-depopulation/)).toBeInTheDocument();
    // Review/Decline flows are explicitly unbuilt (queue #28) — inert buttons.
    expect(screen.getByRole('button', { name: 'Review proposal' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Decline' })).toBeDisabled();
  });

  it('honors the ?from=subsidiary back-link contract', () => {
    renderRoute(
      '/portfolio/contract/fi-shared-pre-fill-agent?from=subsidiary:curasync-clinical',
    );
    const backLinks = screen.getAllByRole('link', {
      name: 'Back to subsidiary plan tab',
    });
    expect(backLinks.length).toBeGreaterThanOrEqual(1);
    expect(backLinks[0]).toHaveAttribute(
      'href',
      '/portfolio/subsidiary/curasync-clinical?tab=plan',
    );
  });
});

describe('PortfolioLayout shell', () => {
  it('wraps routed views with the fixture-dataset banner and forces dark mode', () => {
    renderRoute('/portfolio');
    expect(
      screen.getByText('Beacon Industries — Fixture dataset (demo)'),
    ).toBeInTheDocument();
    expect(setPreferenceSpy).toHaveBeenCalledWith('dark');
  });

  it('labels the subsidiary view in the shell sub-row', () => {
    renderRoute('/portfolio/subsidiary/curasync-clinical');
    const sub = getSubsidiary('curasync-clinical');
    expect(sub).toBeDefined();
    expect(screen.getByText(`Subsidiary COO — ${sub!.name}`)).toBeInTheDocument();
  });
});
