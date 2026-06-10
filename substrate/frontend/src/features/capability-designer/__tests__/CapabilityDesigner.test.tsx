/**
 * Happy-path integration test for the Capability Designer shell (ported
 * from homepoc Phase 2B-1 Stage C). Pre-seeds the React Query cache with a
 * fake domain + a three-node capability hierarchy (one root, one L2 child,
 * one L2 sibling), mounts the Designer behind the ThemeProvider (with a
 * brandingOverride so no network calls leave the test), and asserts:
 *
 *   1. All three {@code displayName} strings appear in the tree.
 *   2. The parent-child structure is present: the root row precedes its
 *      child row in the DOM, and ARIA tree levels reflect the hierarchy.
 *   3. The detail pane renders its empty state (no selection yet) and
 *      the text reads through the t() terminology helper (uses the
 *      overridden "Service" form, proving the designer is integrated
 *      with ThemeProvider rather than hardcoding "capability").
 *
 * Substrate adaptations from the homepoc original:
 *   - Domain resolution flows through useWorkspace, so the domain list is
 *     seeded at ['workspace', 'domains'] (homepoc seeded the ThemeProvider's
 *     ['branding', 'domains-for-branding'] key).
 *   - The Domain wire shape gained {@code metadata} and {@code demo}
 *     (Jackson's name for the isDemo getter — see shared/types/api.ts).
 *
 * Intentionally does NOT exercise edit / create / drag — those live in
 * CapabilityDesignerMutations.test.tsx.
 */
import { describe, expect, it } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import type { BrandingConfig } from '@/types/branding';
import type { Capability } from '@/types/capability';
import type { ApiResponse, Domain } from '@/shared/types/api';
import { ThemeProvider } from '@/app/theme';
import { CapabilityDesigner } from '@/features/capability-designer';

const DOMAIN_ID = '00000000-0000-0000-0000-0000000000aa';
const ROOT_ID   = '00000000-0000-0000-0000-0000000000b1';
const CHILD_A   = '00000000-0000-0000-0000-0000000000c1';
const CHILD_B   = '00000000-0000-0000-0000-0000000000c2';

const FAKE_DOMAIN: Domain = {
  id: DOMAIN_ID,
  name: 'Acme Risk',
  description: null,
  taxonomyConfig: null,
  regulatoryContext: null,
  metadata: null,
  demo: false,
  createdAt: '2026-04-19T00:00:00Z',
  updatedAt: '2026-04-19T00:00:00Z',
};

function cap(
  id: string,
  displayName: string,
  parent: string | null,
  level: number,
  sequenceOrder: number = 0,
): Capability {
  return {
    id,
    domainId: DOMAIN_ID,
    parentCapabilityId: parent,
    name: displayName.toLowerCase().replace(/\s+/g, '-'),
    displayName,
    description: 'Seeded capability for the Stage C render test.',
    level,
    status: 'ACTIVE',
    sourcePackId: null,
    aiGenerated: false,
    metadata: null,
    sequenceOrder,
    createdAt: '2026-04-19T00:00:00Z',
    updatedAt: '2026-04-19T00:00:00Z',
    createdBy: 'test',
    version: 0,
  };
}

const FAKE_CAPABILITIES: Capability[] = [
  cap(ROOT_ID, 'Customer Onboarding',   null,    0, 0),
  cap(CHILD_A, 'Identity Verification', ROOT_ID, 1, 0),
  cap(CHILD_B, 'Document Collection',   ROOT_ID, 1, 1),
];

// BrandingConfig with a distinctive terminology override so we can prove
// the t() helper plumbing flows through the designer. Reads are open per
// BrandingController, so no admin-header concern applies here.
const ACME_BRAND: BrandingConfig = {
  id: '00000000-0000-0000-0000-000000000001',
  domainId: DOMAIN_ID,
  appDisplayName: 'Acme Risk',
  tagline: null,
  colorTokensLight: { background: '0 0% 100%', primary: '217 91% 60%' },
  colorTokensDark:  { background: '240 10% 3.9%', primary: '217 91% 60%' },
  fontBody: 'system-ui, sans-serif',
  fontHeading: 'system-ui, sans-serif',
  fontMono: 'ui-monospace, monospace',
  spacingTokens: null, radiusTokens: null, shadowTokens: null,
  logoPrimaryBlobKey: null, logoMarkBlobKey: null,
  logoLightBlobKey: null, logoDarkBlobKey: null,
  faviconBlobKey: null, loginSplashBlobKey: null,
  terminologyOverrides: {
    capability: { singular: 'Service', plural: 'Services' },
  },
  emailSenderAddress: null, emailSenderName: null, emailReplyTo: null,
  emailHeaderHtml: null, emailFooterHtml: null, emailPrimaryColor: null,
  legalFooterText: null, privacyPolicyUrl: null, termsOfServiceUrl: null,
  customCssUrl: null,
  status: 'ACTIVE',
  createdAt: '2026-04-19T00:00:00Z',
  updatedAt: '2026-04-19T00:00:00Z',
  createdBy: 'test',
  version: 0,
};

function envelope<T>(data: T): ApiResponse<T> {
  return {
    data,
    meta: { timestamp: '2026-04-19T00:00:00Z', requestId: 'test', version: '0.1.0', personaLevel: 'L3_ANALYST' },
    errors: null,
  };
}

describe('CapabilityDesigner — designer shell', () => {
  it('renders the capability hierarchy with parent-child structure and an empty detail pane', () => {
    // staleTime: Infinity ensures setQueryData-seeded entries are treated
    // as fresh, so the CapabilityDesigner's useQuery calls resolve
    // synchronously from cache without firing queryFn (which would hit
    // axios → jsdom → network error in a test environment).
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
    });

    // Pre-seed query data — useWorkspace and the CapabilityDesigner use
    // these exact keys.
    qc.setQueryData(['workspace', 'domains'], envelope([FAKE_DOMAIN]));
    qc.setQueryData(['capabilities', DOMAIN_ID], envelope(FAKE_CAPABILITIES));

    render(
      <QueryClientProvider client={qc}>
        <ThemeProvider brandingOverride={ACME_BRAND}>
          <MemoryRouter>
            <CapabilityDesigner />
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>,
    );

    // --- Assertion 1: all three displayName strings are rendered ---------
    expect(screen.getByText('Customer Onboarding')).toBeInTheDocument();
    expect(screen.getByText('Identity Verification')).toBeInTheDocument();
    expect(screen.getByText('Document Collection')).toBeInTheDocument();

    // --- Assertion 2: parent-child structure ------------------------------
    // Tree rows are ARIA treeitems; children sit after the parent in DOM
    // order and declare deeper ARIA levels.
    const rows = screen.getAllByRole('treeitem');
    const rootRow  = rows.find((r) => within(r).queryByText('Customer Onboarding'));
    const childA   = rows.find((r) => within(r).queryByText('Identity Verification'));
    const childB   = rows.find((r) => within(r).queryByText('Document Collection'));
    expect(rootRow).toBeDefined();
    expect(childA).toBeDefined();
    expect(childB).toBeDefined();

    // DOM order: root precedes both children — this is the core "tree
    // renders the hierarchy" assertion. A flat list rendered in the
    // wrong order would fail either comparison below.
    const order = rows.indexOf.bind(rows);
    expect(order(rootRow!)).toBeLessThan(order(childA!));
    expect(order(rootRow!)).toBeLessThan(order(childB!));

    // The root row must declare aria-expanded (it has children to toggle).
    // We emit this in the tree row for internal nodes.
    expect(rootRow!.getAttribute('aria-expanded')).not.toBeNull();

    // Depth: root row declares aria-level=1; both children declare level=2.
    // Uses standard ARIA tree semantics rather than scraping a specific
    // styling mechanism (paddingLeft vs transform etc. varies by library
    // version).
    expect(rootRow!.getAttribute('aria-level')).toBe('1');
    expect(childA!.getAttribute('aria-level')).toBe('2');
    expect(childB!.getAttribute('aria-level')).toBe('2');

    // --- Assertion 3: terminology override flows through -----------------
    // Header reads "Service Designer" (not "Capability Designer") because
    // the workspace renames capability → Service.
    expect(screen.getByRole('heading', { name: /Service Designer/ })).toBeInTheDocument();

    // Empty-state copy in the detail pane uses the workspace's singular
    // ("service"), proving ThemeProvider's t() is plumbed through the
    // detail pane — not hardcoding "capability".
    expect(screen.getByText(/Select a service to view its details/i)).toBeInTheDocument();
  });
});
