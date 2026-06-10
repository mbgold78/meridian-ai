import type { BrandingConfig } from '@/types/branding';

/**
 * Default "Meridian" BrandingConfig — the bundled fallback used in exactly
 * two situations (see ThemeProvider):
 *
 * 1. DEV error fallback: the live BrandingConfig fetch failed and
 *    `import.meta.env.DEV` is true (backend not running while iterating on
 *    the frontend, or the fixture demo). Production builds do NOT use this
 *    path — a fetch error outside DEV renders the error fallback so a
 *    customer deploy never flashes default-Meridian branding over a real
 *    customer config that merely failed to load.
 *
 * 2. Empty workspace (any build): the domain list loaded successfully but
 *    contains no domains. A fresh substrate deploy starts with an EMPTY
 *    database (production population flows through ingestion), so this is a
 *    legitimate first-run state, not an error. The backend itself serves
 *    transient defaults equivalent to this config the moment a domain exists
 *    with no ACTIVE branding row, so rendering the same defaults pre-domain
 *    is consistent with server behavior.
 *
 * Faithful to the backend canonical defaults in
 * `app/.../branding/BrandingDefaults.java` (appDisplayName, tagline, fonts).
 * Color tokens are intentionally LEFT EMPTY: `applyBrandingToDocument` only
 * writes the keys it is given, so an empty map lets the locked
 * `tokens.css` visual foundation (light + `.dark`) govern unchanged.
 * Terminology overrides are the identity default ({} -> DEFAULT_TERMINOLOGY).
 */
export const DEMO_BRANDING: BrandingConfig = {
  id: '00000000-0000-0000-0000-0000000000de',
  domainId: '00000000-0000-0000-0000-0000000000d0',

  appDisplayName: 'Meridian',
  tagline: 'Capability and workflow designer for enterprise transformation',

  // Empty -> tokens.css (the locked visual foundation) governs both modes.
  colorTokensLight: {},
  colorTokensDark: {},

  fontBody: "'Inter Variable', Inter, system-ui, -apple-system, 'Segoe UI', sans-serif",
  fontHeading: "'Instrument Serif', Georgia, serif",
  fontMono: "'JetBrains Mono', ui-monospace, 'SF Mono', Menlo, Consolas, monospace",

  spacingTokens: null,
  radiusTokens: null,
  shadowTokens: null,

  logoPrimaryBlobKey: null,
  logoMarkBlobKey: null,
  logoLightBlobKey: null,
  logoDarkBlobKey: null,
  faviconBlobKey: null,
  loginSplashBlobKey: null,

  terminologyOverrides: {},

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

  createdAt: '2026-05-31T00:00:00Z',
  updatedAt: '2026-05-31T00:00:00Z',
  createdBy: 'demo-fallback',
  version: 0,
};
