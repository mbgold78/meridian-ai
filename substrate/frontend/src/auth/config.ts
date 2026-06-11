/**
 * Frontend auth-mode configuration (Phase 5 Stage 2; docs/auth.md).
 *
 * The backend's `substrate.auth.mode` ladder has two rungs: `anonymous`
 * (dev default, persona headers trusted verbatim) and `oidc` (generic OIDC
 * resource server, identity from a verified JWT). The SPA must mirror that
 * mode.
 *
 * DEVIATION NOTE (Stage 1 coordination): Stage 1 did NOT add the auth mode to
 * `GET /api/v1/info` (HealthController still returns name/version only), so —
 * per the Stage 2 spec's fallback clause — the mode is a frontend-side config
 * flag. Resolution order, first hit wins:
 *
 *   1. `window.__SUBSTRATE_AUTH__` — runtime-injected JSON (a `<script>` tag
 *      or config.js the customer environment serves next to the bundle). This
 *      is the production path: one build artifact, per-engagement config, no
 *      rebuild (plan §3 — customer-env is production).
 *   2. Vite build-time env (`VITE_SUBSTRATE_AUTH_MODE`, `VITE_OIDC_*`) — the
 *      dev/compose convenience path.
 *   3. Default: `anonymous` — byte-for-byte pre-Phase-5 behavior.
 *
 * When a later backend stage exposes the mode on `/api/v1/info`, this module
 * is the single place to swap in a server probe.
 */

export type AuthMode = 'anonymous' | 'oidc';

/**
 * Per-engagement OIDC settings. Mirrors the backend's `substrate.auth.oidc.*`
 * vocabulary (AuthProperties) so one engagement document configures both
 * halves; no IdP is hard-coded anywhere.
 */
export interface OidcFrontendConfig {
  /** OIDC issuer, e.g. `https://idp.example.com/realms/x` (discovery-based). */
  authority: string;
  /** Public SPA client id registered at the IdP (authorization code + PKCE). */
  clientId: string;
  /** Requested scopes. Default `openid profile`. */
  scope?: string;
  /** Redirect (callback) URI. Default: current origin + '/'. */
  redirectUri?: string;
  /** Post-logout redirect. Default: current origin + '/'. */
  postLogoutRedirectUri?: string;
  /**
   * Dot-path to the roles claim, checked in the ID-token/userinfo profile
   * first, then in the (display-only) decoded access token. Mirrors backend
   * `substrate.auth.oidc.claims.roles`. Default `roles`
   * (Keycloak: `realm_access.roles`).
   */
  rolesClaimPath?: string;
  /** Claim carrying the display label. Default `name`, falling back to `preferred_username`, then `sub`. */
  displayNameClaim?: string;
  /**
   * Ordered role → persona-level projection, FIRST entry whose role the user
   * holds wins — same semantics as backend `substrate.auth.oidc.persona.role-map`.
   * Display-only on this side; the backend derives the authoritative persona
   * from the verified token.
   */
  roleMap?: Record<string, string>;
  /** Persona level shown when no mapped role matches. Default `L3_ANALYST`. */
  defaultLevel?: string;
}

export interface AuthConfig {
  mode: AuthMode;
  /** Present iff mode === 'oidc'. */
  oidc?: OidcFrontendConfig;
}

/** Shape of the optional runtime-injected config object. */
export interface RuntimeAuthConfig {
  mode?: string;
  oidc?: Partial<OidcFrontendConfig>;
}

declare global {
  interface Window {
    /** Runtime-injected auth config (deploy artifact seam — see module doc). */
    __SUBSTRATE_AUTH__?: RuntimeAuthConfig;
  }
}

export const ANONYMOUS_CONFIG: AuthConfig = { mode: 'anonymous' };

function normalizeMode(raw: string | undefined): AuthMode | null {
  if (!raw) return null;
  const mode = raw.trim().toLowerCase();
  if (mode === 'anonymous' || mode === 'oidc') return mode;
  // Mirror the backend's fail-closed posture for unknown modes — except the
  // frontend's safe direction is anonymous (it holds no secrets; the backend
  // still 401s everything if IT is in oidc mode).
  console.warn(`[auth] unknown auth mode '${raw}' — falling back to anonymous`);
  return 'anonymous';
}

function fromRuntime(): AuthConfig | null {
  const runtime = typeof window !== 'undefined' ? window.__SUBSTRATE_AUTH__ : undefined;
  if (!runtime) return null;
  const mode = normalizeMode(runtime.mode);
  if (mode !== 'oidc') return mode ? ANONYMOUS_CONFIG : null;
  return buildOidc(runtime.oidc ?? {}, 'window.__SUBSTRATE_AUTH__');
}

function fromEnv(): AuthConfig | null {
  const env = import.meta.env;
  const mode = normalizeMode(env.VITE_SUBSTRATE_AUTH_MODE as string | undefined);
  if (mode === null) return null;
  if (mode !== 'oidc') return ANONYMOUS_CONFIG;
  return buildOidc(
    {
      authority: env.VITE_OIDC_AUTHORITY as string | undefined,
      clientId: env.VITE_OIDC_CLIENT_ID as string | undefined,
      scope: env.VITE_OIDC_SCOPE as string | undefined,
      rolesClaimPath: env.VITE_OIDC_ROLES_CLAIM_PATH as string | undefined,
      displayNameClaim: env.VITE_OIDC_DISPLAY_NAME_CLAIM as string | undefined,
    },
    'VITE_* env',
  );
}

function buildOidc(partial: Partial<OidcFrontendConfig>, source: string): AuthConfig {
  if (!partial.authority || !partial.clientId) {
    console.warn(
      `[auth] mode is 'oidc' but ${source} is missing authority/clientId — falling back to anonymous`,
    );
    return ANONYMOUS_CONFIG;
  }
  return {
    mode: 'oidc',
    oidc: {
      scope: 'openid profile',
      rolesClaimPath: 'roles',
      displayNameClaim: 'name',
      defaultLevel: 'L3_ANALYST',
      ...stripUndefined(partial),
      authority: partial.authority,
      clientId: partial.clientId,
    },
  };
}

function stripUndefined<T extends object>(obj: T): Partial<T> {
  const out: Partial<T> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== undefined) (out as Record<string, unknown>)[k] = v;
  }
  return out;
}

/**
 * Resolve the active auth config. Called once at app boot (AuthProvider);
 * exported separately so tests can exercise each resolution rung.
 */
export function resolveAuthConfig(): AuthConfig {
  return fromRuntime() ?? fromEnv() ?? ANONYMOUS_CONFIG;
}
