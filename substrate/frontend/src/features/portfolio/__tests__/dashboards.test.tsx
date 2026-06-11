import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, RouterProvider, createMemoryRouter } from 'react-router-dom';
import { PORTFOLIO_ROUTES } from '@/features/portfolio';
import { PortfolioLandingPage } from '@/features/portfolio/PortfolioLandingPage';
import { CorporateOperatingPartnerView } from '@/features/portfolio/CorporateOperatingPartnerView';
import { getSubsidiary } from '@/fixtures/beacon';
import type {
  CascadeActionDto,
  CascadeDetailDto,
  CascadeSummaryDto,
  ContractDetailResponse,
} from '@/api/endpoints';
import type { ApiMeta, Domain } from '@/shared/types/api';

/**
 * Render tests for the four Beacon dashboards (chunk C9 acceptance:
 * "4 dashboards render"). Routed views are mounted through the real
 * PORTFOLIO_ROUTES table via a memory router so path params, search
 * params, and the layout shell are exercised exactly as C8's App
 * router will exercise them.
 *
 * useTheme is stubbed: the layout forces dark mode through it, and the
 * real ThemeProvider needs the branding API (C7's concern, not ours).
 *
 * Cascade read-model swap (engine-spec §11): the API endpoint module is
 * mocked at the barrel. The DEFAULT per-test behavior is backend-absent
 * (every call rejects) which must leave the dashboards rendering the
 * deterministic fixture fallback — the demo-determinism contract. The
 * live-mode describes flip the mocks to resolved envelopes and assert
 * the adapter-driven rendering.
 */

const setPreferenceSpy = vi.hoisted(() => vi.fn());

const apiMocks = vi.hoisted(() => ({
  getDomains: vi.fn(),
  getCascades: vi.fn(),
  getCascadeById: vi.fn(),
  getCascadeActions: vi.fn(),
  getContractByKey: vi.fn(),
  getPortfolio: vi.fn(),
}));

vi.mock('@/app/theme', () => ({
  useTheme: () => ({
    preference: 'system',
    mode: 'dark',
    setPreference: setPreferenceSpy,
    t: (key: string) => key,
    config: {},
  }),
}));

vi.mock('@/api/endpoints', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/endpoints')>()),
  ...apiMocks,
}));

beforeEach(() => {
  // Default: backend absent → degraded/demo fixture fallback.
  apiMocks.getDomains.mockRejectedValue(new Error('backend absent'));
  apiMocks.getCascades.mockRejectedValue(new Error('backend absent'));
  apiMocks.getCascadeById.mockRejectedValue(new Error('backend absent'));
  apiMocks.getCascadeActions.mockRejectedValue(new Error('backend absent'));
  apiMocks.getContractByKey.mockRejectedValue(new Error('backend absent'));
  apiMocks.getPortfolio.mockRejectedValue(new Error('backend absent'));
});

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
}

function renderWithClient(ui: React.ReactElement) {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>{ui}</QueryClientProvider>,
  );
}

function renderRoute(initialPath: string) {
  const router = createMemoryRouter(PORTFOLIO_ROUTES, {
    initialEntries: [initialPath],
  });
  return renderWithClient(<RouterProvider router={router} />);
}

/* ---- live-mode wire fixtures (engine-spec §6.1 shapes) ------------- */

const META: ApiMeta = {
  timestamp: '2026-06-10T00:00:00Z',
  requestId: 'test-request',
  version: 'v1',
  personaLevel: 'L3_ANALYST',
};

function envelope<T>(data: T) {
  return { data, meta: META, errors: null };
}

const DEMO_DOMAIN: Domain = {
  id: 'demo-domain-1',
  name: 'Beacon Industries (demo)',
  description: null,
  taxonomyConfig: null,
  regulatoryContext: null,
  metadata: null,
  demo: true,
  createdAt: '2026-04-01T00:00:00Z',
  updatedAt: '2026-04-01T00:00:00Z',
};

function liveSummary(overrides: Partial<CascadeSummaryDto>): CascadeSummaryDto {
  return {
    id: 'live-cascade-1',
    originatedBy: 'Operating Partner',
    originatedByMemberId: null,
    originatedAt: '2026-04-08T00:00:00Z',
    originalCritique: 'Live critique one arrived from the engine. More detail.',
    acceptanceCriteria: 'Each sub returns a plan or a documented Decline.',
    summary: null,
    currentState: 'in-flight',
    attentionSignal: 'escalating',
    overdue: false,
    levelCount: 2,
    frontierDepth: 1,
    frontierState: 'looking',
    frontierOwnerLabel: 'Curasync COO',
    lastActivityAt: '2026-04-08T00:00:00Z',
    affectedDomainId: null,
    affectedDomainName: null,
    cascadeGroupId: null,
    predecessorCascadeId: null,
    chainSeq: 1,
    ...overrides,
  };
}

const LIVE_DETAIL: CascadeDetailDto = {
  ...liveSummary({
    id: 'live-cascade-detail',
    originalCritique: 'Engine-issued critique with a looking frontier. Details.',
    levelCount: 2,
    frontierDepth: 1,
    frontierState: 'looking',
  }),
  levels: [
    {
      level: 0,
      ownerLabel: 'Operating Partner',
      ownerMemberId: null,
      state: 'cascaded',
      round: 1,
      refinedCritique: 'Refined one-liner passed to L1.',
      declineReasoning: null,
      activatedAt: '2026-04-08T00:00:00Z',
      actedAt: '2026-04-08T00:00:00Z',
      timestamp: '2026-04-08',
      responseDeadline: null,
      overdue: false,
    },
    {
      level: 1,
      ownerLabel: 'Curasync COO',
      ownerMemberId: null,
      state: 'looking',
      round: 1,
      refinedCritique: null,
      declineReasoning: null,
      activatedAt: '2026-04-24T00:00:00Z',
      actedAt: null,
      timestamp: '2026-04-24',
      responseDeadline: null,
      overdue: false,
    },
  ],
  resolution: null,
  siblingDeclineAdvisories: [],
};

const LIVE_ACTIONS: CascadeActionDto[] = [
  {
    id: 'action-1',
    chainSeq: 1,
    depth: 0,
    round: 1,
    action: 'issue',
    actorLabel: 'Operating Partner',
    actorMemberId: null,
    refinedCritique: 'Refined one-liner passed to L1.',
    declineReasoning: null,
    resolutionNote: null,
    nextOwnerLabel: 'Curasync COO',
    actedAt: '2026-04-08T00:00:00Z',
  },
];

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
  it('renders all five zones plus the issued-cascades grid (fixture fallback, backend absent)', () => {
    renderWithClient(
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
    renderWithClient(
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
  it('renders the durable original critique and the level timeline (fixture fallback)', () => {
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

  it('renders a not-found panel for unknown cascade ids', async () => {
    // No fixture match + live query rejected → loading beat, then not-found.
    renderRoute('/portfolio/cascade/c-nope');
    expect(await screen.findByText('Cascade not found')).toBeInTheDocument();
  });
});

describe('cascade read-model swap — live mode (engine-spec §11)', () => {
  beforeEach(() => {
    apiMocks.getDomains.mockResolvedValue(envelope([DEMO_DOMAIN]));
  });

  it('renders live grid rows in operator attention order with signal chips', async () => {
    apiMocks.getCascades.mockResolvedValue(
      envelope([
        liveSummary({
          id: 'live-escalating',
          originalCritique: 'Live escalating critique. Detail.',
          attentionSignal: 'escalating',
          lastActivityAt: '2026-04-24T00:00:00Z',
        }),
        liveSummary({
          id: 'live-decline-pending',
          originalCritique: 'Live decline-pending critique. Detail.',
          attentionSignal: 'decline-pending',
          frontierState: 'declined',
          overdue: true,
          levelCount: 3,
          frontierDepth: 2,
          lastActivityAt: '2026-04-22T00:00:00Z',
        }),
      ]),
    );
    renderWithClient(
      <MemoryRouter>
        <CorporateOperatingPartnerView />
      </MemoryRouter>,
    );
    // Live rows replace the fixture fallback once the API answers.
    expect(
      await screen.findByText('Live decline-pending critique.'),
    ).toBeInTheDocument();
    expect(apiMocks.getCascades).toHaveBeenCalledWith(DEMO_DOMAIN.id);

    const grid = screen.getByRole('region', { name: "Cascades you've issued" });
    const headings = within(grid).getAllByRole('heading', { level: 3 });
    // decline-pending sorts ahead of escalating (spec §11 item 4).
    expect(headings[0]).toHaveTextContent('Live decline-pending critique.');
    expect(headings[1]).toHaveTextContent('Live escalating critique.');
    // Signal chip + separate overdue warn overlay (spec §11 item 3, §2).
    expect(within(grid).getByText('Decline pending')).toBeInTheDocument();
    expect(within(grid).getByText('Escalating')).toBeInTheDocument();
    expect(within(grid).getByText('Overdue')).toBeInTheDocument();
    // Frontier projection drives the meta line.
    expect(within(grid).getByText(/Currently at L2 · 3 level chain/)).toBeInTheDocument();
    // Fixture cascades no longer render.
    expect(
      within(grid).queryByText(/Vendor costs across healthcare subsidiaries/),
    ).not.toBeInTheDocument();
  });

  it('renders the live detail with the LOOKING awaiting-response beat and the actions timeline', async () => {
    apiMocks.getCascadeById.mockResolvedValue(envelope(LIVE_DETAIL));
    apiMocks.getCascadeActions.mockResolvedValue(envelope(LIVE_ACTIONS));
    renderRoute('/portfolio/cascade/live-cascade-detail');

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: /Engine-issued critique with a looking frontier/,
      }),
    ).toBeInTheDocument();
    // LOOKING frontier rows are engine-only (fixtures never contained them).
    expect(screen.getByText('Looking')).toBeInTheDocument();
    expect(
      screen.getByText(/Awaiting response since Apr 24 2026/),
    ).toBeInTheDocument();
    // Additive append-only action timeline (ADR-001 D13).
    expect(await screen.findByText('Action timeline')).toBeInTheDocument();
    expect(screen.getByText('Issued')).toBeInTheDocument();
    // Routing actions surface the next owner (ISSUE → L1 owner).
    expect(screen.getByText(/→ Curasync COO/)).toBeInTheDocument();
    expect(screen.getByText(/Currently at L1, state: looking\./)).toBeInTheDocument();
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

  it('renders the existing 404 fallback panel for unknown contract keys (spec §11.3)', async () => {
    // No fixture match + live query rejected → loading beat, then not-found.
    renderRoute('/portfolio/contract/co-nope');
    expect(await screen.findByText('Contract not found')).toBeInTheDocument();
  });
});

describe('contract read-model swap — live mode (engine-spec contracts.md §11.3)', () => {
  /**
   * Minimal §7.3 detail envelope. Content deliberately differs from the
   * fixture contract with the same key so the live-over-fixture swap is
   * observable in the rendered output.
   */
  const LIVE_CONTRACT: ContractDetailResponse = {
    id: '00000000-0000-0000-0000-00000000c001',
    contractKey: 'co-tribal-knowledge-depopulation',
    type: 'cross-portfolio-opportunity',
    title: 'Live engine-served contract title',
    context: 'Live context paragraph from the read model.',
    status: 'PROPOSED',
    proposedAt: '2026-04-01T00:00:00Z',
    proposedBy: 'operator',
    activatedAt: null,
    resolvedAt: null,
    estimatedValue: '$9.9M live estimated value',
    estimatedValueAmount: null,
    parties: [
      {
        id: 'party-live-1',
        partyLabel: 'Live Party One',
        partyType: 'ORG_UNIT',
        boundEntities: [],
        responseRequired: true,
        response: null,
        commitments: [
          {
            id: 'cmt-live-1',
            text: 'Live commitment text one',
            dueBy: null,
            cadence: null,
            complianceState: 'UNEVALUATED',
            lastEvaluatedAt: null,
          },
        ],
      },
      {
        id: 'party-live-2',
        partyLabel: 'Live Party Two',
        partyType: 'DOMAIN',
        boundEntities: [],
        responseRequired: true,
        response: null,
        commitments: [
          {
            id: 'cmt-live-2',
            text: 'Live commitment text two',
            dueBy: null,
            cadence: null,
            complianceState: 'UNEVALUATED',
            lastEvaluatedAt: null,
          },
        ],
      },
    ],
    enforcement: {
      monitor: 'Live monitoring prose.',
      monitorMechanism: 'MCP live mechanism line rendered verbatim.',
      declineTriggers: [
        { id: 'dt-live-1', text: 'Live decline trigger', armed: false, invocableByPartyId: null },
      ],
    },
    linkedEntities: [
      {
        entityType: 'SUBSIDIARY_SLUG',
        entityId: null,
        slug: 'curasync-clinical',
        label: 'Curasync Clinical Systems',
      },
    ],
    monitors: [],
    breachSummary: { open: 0, bySeverity: {} },
    reviewBoundary: null,
  };

  it('renders the adapter-driven live detail over the fixture, keeping Review/Decline inert (queue #28)', async () => {
    apiMocks.getContractByKey.mockResolvedValue(envelope(LIVE_CONTRACT));
    renderRoute('/portfolio/contract/co-tribal-knowledge-depopulation');

    // Live data replaces the fixture fallback once the API answers.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Live engine-served contract title' }),
    ).toBeInTheDocument();
    expect(apiMocks.getContractByKey).toHaveBeenCalledWith(
      'co-tribal-knowledge-depopulation',
    );
    expect(screen.getByText('Live Party One')).toBeInTheDocument();
    expect(screen.getByText('Live commitment text two')).toBeInTheDocument();
    // VERBATIM enforcement text in the font-mono mechanism panel (§7.3).
    expect(
      screen.getByText('MCP live mechanism line rendered verbatim.'),
    ).toBeInTheDocument();
    expect(screen.getByText('$9.9M live estimated value')).toBeInTheDocument();
    // Fixture title no longer renders.
    expect(
      screen.queryByText(/Tribal-knowledge depopulation across four/),
    ).not.toBeInTheDocument();
    // Inert affordances stay inert in live mode (queue #28 — do NOT wire).
    expect(screen.getByRole('button', { name: 'Review proposal' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Decline' })).toBeDisabled();
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
