/**
 * Integration tests for the theming foundation.
 *
 * Tests 1–2 are the homepoc Phase 2B-1 suite, ported as-is: mount
 * ThemeProvider with a non-default BrandingConfig (distinct color tokens +
 * distinct terminology override) and assert both surfaces:
 *   1. CSS custom properties on <html> match the config's colorTokensLight.
 *   2. A component calling {@code useTerminology().t(...)} returns the
 *      overridden term, not the DEFAULT_TERMINOLOGY value.
 *
 * Tests 3–4 cover the substrate port's workspace-resolution rework: the
 * provider no longer blindly grabs the first domain — it resolves the
 * workspace via the explicit policy (non-demo preference) and falls back to
 * the bundled default branding when the deploy has no domains yet.
 */
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { BrandingConfig } from '@/types/branding';
import type { ApiResponse, Domain } from '@/shared/types/api';
import { getActiveBrandingConfig, getDomains } from '@/api/endpoints';
import { ThemeProvider, useTerminology, useTheme } from '@/app/theme';

vi.mock('@/api/endpoints', () => ({
  getDomains: vi.fn(),
  getActiveBrandingConfig: vi.fn(),
}));

const mockedGetDomains = vi.mocked(getDomains);
const mockedGetActiveBrandingConfig = vi.mocked(getActiveBrandingConfig);

function envelope<T>(data: T): ApiResponse<T> {
  return {
    data,
    meta: {
      timestamp: '2026-06-09T00:00:00Z',
      requestId: 'test-request',
      version: 'v1',
      personaLevel: 'L3_ANALYST',
    },
    errors: null,
  };
}

function makeDomain(id: string, name: string, demo: boolean): Domain {
  return {
    id,
    name,
    description: null,
    taxonomyConfig: null,
    regulatoryContext: null,
    metadata: null,
    demo,
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
  };
}

const ACME_BRAND: BrandingConfig = {
  id: '00000000-0000-0000-0000-000000000001',
  domainId: '00000000-0000-0000-0000-0000000000aa',
  appDisplayName: 'Acme Risk',
  tagline: 'Test tagline',

  // Distinct from tokens.css defaults so the assertion proves the CSS
  // var was actually overwritten rather than reading the built-in value.
  colorTokensLight: {
    background: '330 100% 99%',
    primary:    '330 80% 40%',
    border:     '330 50% 70%',
  },
  colorTokensDark: {
    background: '330 20% 10%',
    primary:    '330 80% 60%',
    border:     '330 30% 30%',
  },

  fontBody: 'Acme Sans, sans-serif',
  fontHeading: 'Acme Display, serif',
  fontMono: 'Acme Mono, monospace',

  spacingTokens: null,
  radiusTokens: null,
  shadowTokens: null,

  logoPrimaryBlobKey: null,
  logoMarkBlobKey: null,
  logoLightBlobKey: null,
  logoDarkBlobKey: null,
  faviconBlobKey: null,
  loginSplashBlobKey: null,

  // Distinct terminology so we can tell we read the override, not the default.
  terminologyOverrides: {
    capability: { singular: 'Service',  plural: 'Services' },
    // Note: 'process' NOT overridden — t('process','plural') should fall
    // back to the DEFAULT_TERMINOLOGY map ("Processes"). Covered in a
    // follow-on assertion to prove fallback actually runs.
  },

  emailSenderAddress: null,
  emailSenderName: null,
  emailReplyTo: null,
  emailHeaderHtml: null,
  emailFooterHtml: null,
  emailPrimaryColor: null,

  legalFooterText: null,
  privacyPolicyUrl: null,
  termsOfServiceUrl: null,

  customCssUrl: null,

  status: 'ACTIVE',

  createdAt: '2026-04-19T00:00:00Z',
  updatedAt: '2026-04-19T00:00:00Z',
  createdBy: 'test',
  version: 0,
};

function TerminologyProbe() {
  const { t } = useTerminology();
  return (
    <ul>
      <li data-testid="cap-plural">{t('capability', 'plural')}</li>
      <li data-testid="cap-singular">{t('capability', 'singular')}</li>
      <li data-testid="process-plural">{t('process', 'plural')}</li>
    </ul>
  );
}

function ConfigProbe() {
  const { config } = useTheme();
  return <span data-testid="app-name">{config.appDisplayName}</span>;
}

function renderWithProviders(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ThemeProvider — theming foundation', () => {
  it('applies custom BrandingConfig CSS vars to <html> and overrides terminology via t()', () => {
    renderWithProviders(
      <ThemeProvider brandingOverride={ACME_BRAND}>
        <TerminologyProbe />
      </ThemeProvider>,
    );

    // --- Surface 1: CSS custom properties on <html> -----------------------
    const html = document.documentElement;
    expect(html.style.getPropertyValue('--background').trim())
      .toBe('330 100% 99%');
    expect(html.style.getPropertyValue('--primary').trim())
      .toBe('330 80% 40%');
    expect(html.style.getPropertyValue('--border').trim())
      .toBe('330 50% 70%');
    // Font-family triplet came through too:
    expect(html.style.getPropertyValue('--font-body').trim())
      .toBe('Acme Sans, sans-serif');

    // --- Surface 2: t() terminology helper --------------------------------
    // Override wins when present:
    expect(screen.getByTestId('cap-plural').textContent).toBe('Services');
    expect(screen.getByTestId('cap-singular').textContent).toBe('Service');
    // Missing key → DEFAULT_TERMINOLOGY fallback (not the raw internal
    // identifier "process"):
    expect(screen.getByTestId('process-plural').textContent).toBe('Processes');

    // Override path never touched the network layer:
    expect(mockedGetDomains).not.toHaveBeenCalled();
    expect(mockedGetActiveBrandingConfig).not.toHaveBeenCalled();
  });

  /**
   * Dark-mode happy path: prove that
   *   (a) when preference is 'dark', <html> gets the 'dark' class AND the
   *       inline CSS vars come from colorTokensDark, not colorTokensLight;
   *   (b) flipping back to 'light' removes the class AND re-applies
   *       colorTokensLight, with no residual dark values bleeding through.
   *
   * This exercises the cleanup-before-reapply path in
   * {@code applyBrandingToDocument}'s returned cleanup function, which
   * otherwise goes untested. The assertion pattern is "delete the cleanup
   * line in applyBrandingToDocument, watch this test fail".
   */
  it('applies colorTokensDark in dark mode and cleanly restores light tokens on flip', () => {
    // Force dark at initial render via the persisted preference — matches
    // how a returning user with dark-pinned preference boots.
    window.localStorage.setItem('meridian-theme', 'dark');

    function ModeFlipProbe() {
      const { setPreference, mode } = useTheme();
      return (
        <>
          <span data-testid="mode">{mode}</span>
          <button
            type="button"
            data-testid="to-light"
            onClick={() => setPreference('light')}
          >
            light
          </button>
        </>
      );
    }

    renderWithProviders(
      <ThemeProvider brandingOverride={ACME_BRAND}>
        <ModeFlipProbe />
      </ThemeProvider>,
    );

    const html = document.documentElement;

    // --- Initial dark state ----------------------------------------------
    // html must carry the .dark class when preference=dark
    expect(html.classList.contains('dark')).toBe(true);
    expect(screen.getByTestId('mode').textContent).toBe('dark');

    // Dark tokens from ACME_BRAND.colorTokensDark:
    expect(html.style.getPropertyValue('--background').trim()).toBe('330 20% 10%');
    expect(html.style.getPropertyValue('--primary').trim()).toBe('330 80% 60%');
    expect(html.style.getPropertyValue('--border').trim()).toBe('330 30% 30%');
    // Sanity: the light values are NOT leaking through from a previous apply.
    expect(html.style.getPropertyValue('--background').trim()).not.toBe('330 100% 99%');

    // --- Flip to light ---------------------------------------------------
    act(() => {
      fireEvent.click(screen.getByTestId('to-light'));
    });

    expect(html.classList.contains('dark')).toBe(false);
    expect(screen.getByTestId('mode').textContent).toBe('light');

    // Light tokens from ACME_BRAND.colorTokensLight:
    expect(html.style.getPropertyValue('--background').trim()).toBe('330 100% 99%');
    expect(html.style.getPropertyValue('--primary').trim()).toBe('330 80% 40%');
    expect(html.style.getPropertyValue('--border').trim()).toBe('330 50% 70%');

    // Persisted preference flipped with the state:
    expect(window.localStorage.getItem('meridian-theme')).toBe('light');
  });

  /**
   * Substrate workspace-resolution rework: with multiple domains the
   * provider must fetch branding for the resolved workspace domain (first
   * NON-demo in server order) — not blindly data[0] as homepoc did.
   */
  it('resolves the workspace domain (non-demo preferred) and fetches its branding', async () => {
    const demoDomain = makeDomain('11111111-1111-1111-1111-111111111111', 'Demo Sandbox', true);
    const realDomain = makeDomain('00000000-0000-0000-0000-0000000000aa', 'Acme Engagement', false);
    // Demo domain FIRST — the homepoc data[0] behavior would pick it.
    mockedGetDomains.mockResolvedValue(envelope([demoDomain, realDomain]));
    mockedGetActiveBrandingConfig.mockResolvedValue(envelope(ACME_BRAND));

    renderWithProviders(
      <ThemeProvider>
        <ConfigProbe />
      </ThemeProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('app-name').textContent).toBe('Acme Risk');
    });
    expect(mockedGetActiveBrandingConfig).toHaveBeenCalledTimes(1);
    expect(mockedGetActiveBrandingConfig).toHaveBeenCalledWith(realDomain.id);
    // The resolved config's tokens were applied:
    expect(document.documentElement.style.getPropertyValue('--primary').trim())
      .toBe('330 80% 40%');
  });

  /**
   * Fresh-deploy fallback policy: a successfully-loaded but EMPTY domain
   * list (production substrate starts with an empty DB; ingestion populates
   * it) renders the bundled default branding instead of blocking the app on
   * an infinite loading state (homepoc's behavior) — and never calls the
   * branding endpoint without a domain id.
   */
  it('falls back to default branding when the deploy has no domains yet', async () => {
    mockedGetDomains.mockResolvedValue(envelope<Domain[]>([]));

    renderWithProviders(
      <ThemeProvider>
        <ConfigProbe />
      </ThemeProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('app-name').textContent).toBe('Meridian');
    });
    expect(mockedGetActiveBrandingConfig).not.toHaveBeenCalled();
  });
});
