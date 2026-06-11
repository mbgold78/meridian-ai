import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import type { OidcFrontendConfig } from './config';

/**
 * UserManager factory — authorization code + PKCE (oidc-client-ts defaults:
 * response_type 'code' with S256 PKCE; no implicit flow, no client secret —
 * this is a public SPA client).
 *
 * - automaticSilentRenew: refresh handled by the library off the refresh
 *   token (`offline_access` not requested by default; IdPs that issue refresh
 *   tokens to public clients — Keycloak does — renew silently, others
 *   re-prompt on expiry via the silent-renew-error path in AuthProvider).
 * - userStore in sessionStorage: tokens never persist across browser exits;
 *   survive reloads within the tab (matches the short-lived-token posture in
 *   docs/auth.md §6).
 */
export function createUserManager(config: OidcFrontendConfig): UserManager {
  const origin = window.location.origin;
  return new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri ?? `${origin}/`,
    post_logout_redirect_uri: config.postLogoutRedirectUri ?? `${origin}/`,
    scope: config.scope ?? 'openid profile',
    response_type: 'code',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
}
