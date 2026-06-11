import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { User, UserManager } from 'oidc-client-ts';
import { AuthContext, ANONYMOUS_AUTH, type AuthContextValue, type AuthStatus } from './AuthContext';
import { resolveAuthConfig, type AuthConfig, type OidcFrontendConfig } from './config';
import {
  registerUnauthorizedHandler,
  setAccessToken,
  setAuthMode,
} from './runtime';
import { createUserManager } from './userManager';
import { displayLabelFromUser, personaLevelFromUser } from './claims';

interface AuthProviderProps {
  children: ReactNode;
  /** Test seam — defaults to resolveAuthConfig() (window runtime → VITE env → anonymous). */
  config?: AuthConfig;
  /** Test seam — defaults to createUserManager(config.oidc) in oidc mode. */
  userManager?: UserManager;
}

/**
 * Auth boundary for the SPA (Phase 5 Stage 2).
 *
 * anonymous mode (default): a pure pass-through — children render
 * synchronously under the constant ANONYMOUS_AUTH context value. No effects,
 * no network, no DOM: the pre-Phase-5 UX byte-for-byte.
 *
 * oidc mode: OIDC authorization code + PKCE against the configured authority.
 * Children are withheld until a verified user exists, so no API call ever
 * fires unauthenticated; the access token is pushed into the auth runtime for
 * the axios interceptor and the SSE URL builder; silent renew keeps it fresh;
 * a backend 401 or a failed renew triggers an interactive re-auth redirect.
 */
export function AuthProvider({ children, config, userManager }: AuthProviderProps) {
  // Resolve once for the lifetime of the provider (mode changes require a reload).
  const resolvedRef = useRef<AuthConfig | null>(null);
  if (resolvedRef.current === null) {
    resolvedRef.current = config ?? resolveAuthConfig();
  }
  const resolved = resolvedRef.current;

  if (resolved.mode !== 'oidc' || !resolved.oidc) {
    return <AuthContext.Provider value={ANONYMOUS_AUTH}>{children}</AuthContext.Provider>;
  }
  return (
    <OidcAuthBoundary oidcConfig={resolved.oidc} injectedManager={userManager}>
      {children}
    </OidcAuthBoundary>
  );
}

/** True when the current URL carries an authorization-code response. */
function hasAuthResponseParams(search: string): boolean {
  const params = new URLSearchParams(search);
  return params.has('state') && (params.has('code') || params.has('error'));
}

/** Strip the OIDC response params, preserving any application params. */
function urlWithoutAuthParams(): string {
  const url = new URL(window.location.href);
  ['code', 'state', 'session_state', 'iss', 'error', 'error_description'].forEach((p) =>
    url.searchParams.delete(p),
  );
  return url.pathname + (url.searchParams.toString() ? `?${url.searchParams.toString()}` : '') + url.hash;
}

interface OidcAuthBoundaryProps {
  children: ReactNode;
  oidcConfig: OidcFrontendConfig;
  injectedManager?: UserManager;
}

function OidcAuthBoundary({ children, oidcConfig, injectedManager }: OidcAuthBoundaryProps) {
  const managerRef = useRef<UserManager | null>(null);
  if (managerRef.current === null) {
    // Flip the runtime BEFORE anything else renders so the very first axios
    // request already speaks Bearer (and never leaks a persona header).
    setAuthMode('oidc');
    managerRef.current = injectedManager ?? createUserManager(oidcConfig);
  }
  const manager = managerRef.current;

  const [status, setStatus] = useState<AuthStatus>('initializing');
  const [user, setUser] = useState<User | null>(null);
  const redirectingRef = useRef(false);
  const callbackHandledRef = useRef(false);

  const adoptUser = useCallback((next: User | null) => {
    setAccessToken(next && !next.expired ? next.access_token : null);
    setUser(next);
    setStatus(next && !next.expired ? 'authenticated' : 'unauthenticated');
  }, []);

  const beginSignIn = useCallback(() => {
    if (redirectingRef.current) return;
    redirectingRef.current = true;
    setStatus('unauthenticated');
    manager
      .signinRedirect({ state: { returnTo: window.location.pathname + window.location.search } })
      .catch((err: unknown) => {
        console.error('[auth] signinRedirect failed', err);
        redirectingRef.current = false;
        setStatus('error');
      });
  }, [manager]);

  const signOut = useCallback(() => {
    setAccessToken(null);
    manager.signoutRedirect().catch((err: unknown) => {
      console.error('[auth] signoutRedirect failed', err);
    });
  }, [manager]);

  useEffect(() => {
    const onUserLoaded = (loaded: User) => adoptUser(loaded);
    const onUserUnloaded = () => adoptUser(null);
    const onSilentRenewError = (err: unknown) => {
      // Refresh failed (refresh token expired / IdP session gone) — the next
      // API call would 401 anyway; go interactive now.
      console.warn('[auth] silent renew failed — starting interactive sign-in', err);
      beginSignIn();
    };
    manager.events.addUserLoaded(onUserLoaded);
    manager.events.addUserUnloaded(onUserUnloaded);
    manager.events.addSilentRenewError(onSilentRenewError);
    // Backend said 401 (revoked/expired before renewal won the race) → re-auth.
    registerUnauthorizedHandler(beginSignIn);

    const init = async () => {
      try {
        if (hasAuthResponseParams(window.location.search) && !callbackHandledRef.current) {
          callbackHandledRef.current = true; // StrictMode double-effect guard
          const fromCallback = await manager.signinRedirectCallback();
          // Deep-link restoration: beginSignIn captured where the user was
          // (state.returnTo) — land them back there, not on the redirect
          // URI. Same-origin paths only (defense against a tampered state).
          const returnTo = (fromCallback?.state as { returnTo?: string } | undefined)?.returnTo;
          const safeReturnTo =
            typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//')
              ? returnTo
              : null;
          window.history.replaceState({}, document.title, safeReturnTo ?? urlWithoutAuthParams());
          adoptUser(fromCallback);
          return;
        }
        const existing = await manager.getUser();
        if (existing && !existing.expired) {
          adoptUser(existing);
          return;
        }
        beginSignIn();
      } catch (err) {
        console.error('[auth] sign-in initialization failed', err);
        setStatus('error');
      }
    };
    void init();

    return () => {
      manager.events.removeUserLoaded(onUserLoaded);
      manager.events.removeUserUnloaded(onUserUnloaded);
      manager.events.removeSilentRenewError(onSilentRenewError);
      registerUnauthorizedHandler(null);
    };
  }, [manager, adoptUser, beginSignIn]);

  const value = useMemo<AuthContextValue>(
    () => ({
      mode: 'oidc',
      status,
      identityLabel: user ? displayLabelFromUser(user, oidcConfig) : null,
      personaLevel: user ? personaLevelFromUser(user, oidcConfig) : null,
      signIn: beginSignIn,
      signOut,
    }),
    [status, user, oidcConfig, beginSignIn, signOut],
  );

  return (
    <AuthContext.Provider value={value}>
      {status === 'authenticated' ? (
        children
      ) : (
        <AuthGate status={status} onRetry={beginSignIn} />
      )}
    </AuthContext.Provider>
  );
}

/**
 * Shown while unauthenticated: children are withheld so the app never fires
 * unauthenticated API calls (the backend would 401 every /api/** anyway).
 */
function AuthGate({ status, onRetry }: { status: AuthStatus; onRetry: () => void }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex h-screen w-screen flex-col items-center justify-center gap-3 bg-background text-foreground-muted"
    >
      {status === 'error' ? (
        <>
          <p className="text-sm">Sign-in failed. Check the identity provider connection.</p>
          <button
            onClick={onRetry}
            className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium text-foreground hover:bg-muted"
          >
            Try again
          </button>
        </>
      ) : (
        <p className="text-sm">Signing in&hellip;</p>
      )}
    </div>
  );
}
