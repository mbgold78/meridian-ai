/**
 * AuthProvider behavior in both modes (mocked UserManager — no real IdP):
 *  - anonymous: pure pass-through, zero auth machinery (pre-Phase-5 UX).
 *  - oidc: code+PKCE session adoption, redirect to sign-in when
 *    unauthenticated, redirect-callback handling + URL cleanup, token into
 *    the runtime for the axios/SSE layers, 401 → re-auth, sign-out.
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { User, UserManager } from 'oidc-client-ts';
import { AuthProvider } from '@/auth/AuthProvider';
import { useAuth } from '@/auth/useAuth';
import type { AuthConfig } from '@/auth/config';
import { getAccessToken, getAuthMode, notifyUnauthorized, resetAuthRuntime } from '@/auth/runtime';

const OIDC_CONFIG: AuthConfig = {
  mode: 'oidc',
  oidc: {
    authority: 'https://idp.example.test/realms/substrate',
    clientId: 'substrate-spa',
    rolesClaimPath: 'realm_access.roles',
    roleMap: { 'substrate-admin': 'ADMIN', 'substrate-coo': 'EXECUTIVE' },
    defaultLevel: 'L3_ANALYST',
  },
};

const DEV_ADMIN = {
  access_token: 'tok-dev-admin',
  expired: false,
  profile: {
    sub: '7f3e2a10-0000-4000-8000-000000000001',
    name: 'Dev Admin',
    realm_access: { roles: ['substrate-admin'] },
  },
} as unknown as User;

function fakeUserManager(overrides: Partial<Record<string, unknown>> = {}): UserManager {
  return {
    getUser: vi.fn(async () => null),
    signinRedirect: vi.fn(async () => undefined),
    signinRedirectCallback: vi.fn(async () => DEV_ADMIN),
    signoutRedirect: vi.fn(async () => undefined),
    events: {
      addUserLoaded: vi.fn(),
      removeUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
      removeUserUnloaded: vi.fn(),
      addSilentRenewError: vi.fn(),
      removeSilentRenewError: vi.fn(),
    },
    ...overrides,
  } as unknown as UserManager;
}

function Probe() {
  const auth = useAuth();
  return (
    <div>
      <span data-testid="mode">{auth.mode}</span>
      <span data-testid="status">{auth.status}</span>
      <span data-testid="label">{auth.identityLabel ?? ''}</span>
      <span data-testid="level">{auth.personaLevel ?? ''}</span>
      <button onClick={auth.signOut}>probe-sign-out</button>
    </div>
  );
}

afterEach(() => {
  resetAuthRuntime();
  window.history.replaceState({}, '', '/');
});

describe('AuthProvider — anonymous mode (default)', () => {
  it('renders children synchronously with the no-op context and touches no auth runtime', () => {
    render(
      <AuthProvider config={{ mode: 'anonymous' }}>
        <Probe />
      </AuthProvider>,
    );
    // No async gate: children are present immediately.
    expect(screen.getByTestId('mode').textContent).toBe('anonymous');
    expect(screen.getByTestId('status').textContent).toBe('disabled');
    expect(screen.getByTestId('label').textContent).toBe('');
    expect(getAuthMode()).toBe('anonymous');
    expect(getAccessToken()).toBeNull();
  });
});

describe('AuthProvider — oidc mode', () => {
  it('adopts an existing session: token into runtime, identity + persona from claims', async () => {
    const manager = fakeUserManager({ getUser: vi.fn(async () => DEV_ADMIN) });
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    expect(screen.getByTestId('mode').textContent).toBe('oidc');
    expect(screen.getByTestId('label').textContent).toBe('Dev Admin');
    expect(screen.getByTestId('level').textContent).toBe('ADMIN');
    expect(getAuthMode()).toBe('oidc');
    expect(getAccessToken()).toBe('tok-dev-admin');
    expect(manager.signinRedirect).not.toHaveBeenCalled();
  });

  it('withholds children and starts signinRedirect when no session exists', async () => {
    const manager = fakeUserManager();
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(manager.signinRedirect).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('mode')).toBeNull(); // children never rendered
    expect(screen.getByRole('status')).toHaveTextContent(/signing in/i);
  });

  it('completes the code+PKCE redirect callback and cleans the URL (app params kept)', async () => {
    window.history.replaceState({}, '', '/?code=abc&state=xyz&session_state=s1&view=risks');
    const manager = fakeUserManager();
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    expect(manager.signinRedirectCallback).toHaveBeenCalledTimes(1);
    expect(window.location.search).toBe('?view=risks');
    expect(getAccessToken()).toBe('tok-dev-admin');
  });

  it('restores the captured deep link (state.returnTo) after the redirect callback', async () => {
    window.history.replaceState({}, '', '/?code=abc&state=xyz&session_state=s1');
    const withReturnTo = {
      ...DEV_ADMIN,
      state: { returnTo: '/portfolio/cascade/abc-123?tab=chain' },
    } as unknown as User;
    const manager = fakeUserManager({
      signinRedirectCallback: vi.fn(async () => withReturnTo),
    });
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    expect(window.location.pathname).toBe('/portfolio/cascade/abc-123');
    expect(window.location.search).toBe('?tab=chain');
  });

  it('ignores a non-same-origin returnTo and falls back to URL cleaning', async () => {
    window.history.replaceState({}, '', '/?code=abc&state=xyz');
    const evil = {
      ...DEV_ADMIN,
      state: { returnTo: '//evil.example/phish' },
    } as unknown as User;
    const manager = fakeUserManager({
      signinRedirectCallback: vi.fn(async () => evil),
    });
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    expect(window.location.pathname).toBe('/');
    expect(window.location.search).toBe('');
  });

  it('a backend 401 (notifyUnauthorized) triggers an interactive re-auth redirect', async () => {
    const manager = fakeUserManager({ getUser: vi.fn(async () => DEV_ADMIN) });
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    act(() => notifyUnauthorized());
    await waitFor(() => expect(manager.signinRedirect).toHaveBeenCalledTimes(1));
  });

  it('signOut clears the runtime token and starts the RP-initiated logout redirect', async () => {
    const manager = fakeUserManager({ getUser: vi.fn(async () => DEV_ADMIN) });
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    fireEvent.click(screen.getByRole('button', { name: 'probe-sign-out' }));
    await waitFor(() => expect(manager.signoutRedirect).toHaveBeenCalledTimes(1));
    expect(getAccessToken()).toBeNull();
  });

  it('keeps the runtime token fresh on silent renew (userLoaded event)', async () => {
    let userLoadedHandler: ((u: User) => void) | undefined;
    const manager = fakeUserManager({
      getUser: vi.fn(async () => DEV_ADMIN),
      events: {
        addUserLoaded: vi.fn((fn: (u: User) => void) => {
          userLoadedHandler = fn;
        }),
        removeUserLoaded: vi.fn(),
        addUserUnloaded: vi.fn(),
        removeUserUnloaded: vi.fn(),
        addSilentRenewError: vi.fn(),
        removeSilentRenewError: vi.fn(),
      },
    });
    render(
      <AuthProvider config={OIDC_CONFIG} userManager={manager}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    const renewed = {
      ...(DEV_ADMIN as unknown as Record<string, unknown>),
      access_token: 'tok-renewed',
    } as unknown as User;
    act(() => userLoadedHandler?.(renewed));
    await waitFor(() => expect(getAccessToken()).toBe('tok-renewed'));
  });
});
