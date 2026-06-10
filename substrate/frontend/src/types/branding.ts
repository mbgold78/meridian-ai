/**
 * TypeScript types mirroring the backend BrandingConfig entity. These must
 * stay in sync with
 * {@code common/src/main/java/com/meridian/substrate/common/domain/BrandingConfig.java}
 * on the backend; the {@code db/migration/common/V2__canonical_model.sql}
 * squash migration is the canonical schema source (the ACTIVE-per-domain
 * partial index lives in {@code db/migration/postgresql/V2_1__active_branding_partial_index.sql}).
 */

export type BrandingConfigStatus = 'DRAFT' | 'ACTIVE';

/**
 * Per-key terminology entry. Both forms are required — the t() helper
 * resolves either based on the `form` argument and will throw in dev mode
 * if a key is present but missing one of the two forms (a shape bug).
 */
export interface TerminologyEntry {
  singular: string;
  plural: string;
}

/**
 * The full terminology override map. Keys are stable internal names
 * ("capability", "process", "domain", "finding", "system", "reader", ...);
 * values are the customer-facing labels.
 *
 * Backend persists this as a JSON object column; the frontend treats it as
 * a lookup table for the t() helper.
 */
export type TerminologyMap = Record<string, TerminologyEntry>;

/**
 * HSL-triplet value as stored in BrandingConfig.colorTokensLight/Dark.
 * Shape: "H S% L%" e.g. "217 91% 60%". Injected into CSS custom properties
 * and consumed by Tailwind via `hsl(var(--token))`.
 */
export type HslTriplet = string;

export type ColorTokens = Record<string, HslTriplet | string>;

/**
 * Full BrandingConfig shape as the backend serializes it through
 * GET /api/v1/branding/configs/active. All fields present in
 * common/.../domain/BrandingConfig.java are mirrored here; optional fields
 * are nullable in the DTO.
 */
export interface BrandingConfig {
  id: string;
  domainId: string;

  appDisplayName: string;
  tagline: string | null;

  colorTokensLight: ColorTokens;
  colorTokensDark: ColorTokens;

  fontBody: string;
  fontHeading: string;
  fontMono: string;

  spacingTokens: Record<string, string> | null;
  radiusTokens: Record<string, string> | null;
  shadowTokens: Record<string, string> | null;

  logoPrimaryBlobKey: string | null;
  logoMarkBlobKey: string | null;
  logoLightBlobKey: string | null;
  logoDarkBlobKey: string | null;
  faviconBlobKey: string | null;
  loginSplashBlobKey: string | null;

  terminologyOverrides: TerminologyMap;

  emailSenderAddress: string | null;
  emailSenderName: string | null;
  emailReplyTo: string | null;
  emailHeaderHtml: string | null;
  emailFooterHtml: string | null;
  emailPrimaryColor: string | null;

  legalFooterText: string | null;
  privacyPolicyUrl: string | null;
  termsOfServiceUrl: string | null;

  customCssUrl: string | null;

  status: BrandingConfigStatus;

  createdAt: string;
  updatedAt: string;
  createdBy: string;
  version: number;
}
