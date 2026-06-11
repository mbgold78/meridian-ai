/**
 * Module-level auth runtime shared between the React tree (AuthProvider) and
 * non-React modules (api/client.ts axios interceptors, api/endpoints/chat.ts
 * EventSource URL builder).
 *
 * Defaults are the anonymous posture, so simply never touching this module
 * (the anonymous-mode case) leaves the wire behavior byte-identical to
 * pre-Phase-5: persona header attached, no Authorization header, 401s pass
 * through untouched.
 */

import type { AuthMode } from './config';

interface AuthRuntimeState {
  mode: AuthMode;
  accessToken: string | null;
  onUnauthorized: (() => void) | null;
}

const state: AuthRuntimeState = {
  mode: 'anonymous',
  accessToken: null,
  onUnauthorized: null,
};

export function setAuthMode(mode: AuthMode): void {
  state.mode = mode;
}

export function getAuthMode(): AuthMode {
  return state.mode;
}

/** Kept current by AuthProvider on every user load / silent renew / logout. */
export function setAccessToken(token: string | null): void {
  state.accessToken = token;
}

export function getAccessToken(): string | null {
  return state.accessToken;
}

/**
 * AuthProvider registers the 401 → interactive re-auth hook here; the axios
 * response interceptor calls it. Anonymous mode never registers one, so 401
 * handling is unchanged from pre-Phase-5.
 */
export function registerUnauthorizedHandler(handler: (() => void) | null): void {
  state.onUnauthorized = handler;
}

export function notifyUnauthorized(): void {
  state.onUnauthorized?.();
}

/** Test seam — restore the anonymous-default state between tests. */
export function resetAuthRuntime(): void {
  state.mode = 'anonymous';
  state.accessToken = null;
  state.onUnauthorized = null;
}
