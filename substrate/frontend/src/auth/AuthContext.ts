import { createContext } from 'react';
import type { AuthMode } from './config';

/**
 * `disabled` = anonymous mode (no auth layer at all); the other states only
 * occur under oidc.
 */
export type AuthStatus = 'disabled' | 'initializing' | 'authenticated' | 'unauthenticated' | 'error';

export interface AuthContextValue {
  mode: AuthMode;
  status: AuthStatus;
  /** Display label from claims (name → preferred_username → sub); null in anonymous mode. */
  identityLabel: string | null;
  /** Persona level derived from claims (display-only mirror of the backend mapping); null in anonymous mode. */
  personaLevel: string | null;
  /** Starts the code+PKCE redirect. No-op in anonymous mode. */
  signIn: () => void;
  /** RP-initiated logout redirect. No-op in anonymous mode. */
  signOut: () => void;
}

export const ANONYMOUS_AUTH: AuthContextValue = {
  mode: 'anonymous',
  status: 'disabled',
  identityLabel: null,
  personaLevel: null,
  signIn: () => undefined,
  signOut: () => undefined,
};

/**
 * Defaults to the anonymous value so components calling useAuth render
 * byte-identically when no AuthProvider is mounted (e.g. existing tests that
 * render PersonaSwitcher bare).
 */
export const AuthContext = createContext<AuthContextValue>(ANONYMOUS_AUTH);
