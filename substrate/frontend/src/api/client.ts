import axios from 'axios';
import { getAccessToken, getAuthMode, notifyUnauthorized } from '@/auth/runtime';

export const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Identity attachment — mode-aware (Phase 5 Stage 2, docs/auth.md):
//
//  anonymous (default): the pre-Phase-5 behavior verbatim — the X-Persona-Level
//  header is the (spoofable, accepted-posture) identity source, read from the
//  localStorage key shared with PersonaSwitcher — keep them in sync.
//
//  oidc: the verified JWT is the identity source. Bearer token only; the
//  persona header is intentionally NOT sent (the backend ignores it with a
//  WARN — S9 severed — so sending it would only add log noise).
apiClient.interceptors.request.use((config) => {
  if (getAuthMode() === 'oidc') {
    const token = getAccessToken();
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }
    return config;
  }
  const personaLevel = localStorage.getItem('meridian-persona-level') || 'L3_ANALYST';
  config.headers['X-Persona-Level'] = personaLevel;
  return config;
});

// 401 → interactive re-auth, oidc mode only. In anonymous mode there is no
// registered handler and the rejection passes through byte-identically.
apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (getAuthMode() === 'oidc') {
      const status = (error as { response?: { status?: number } } | null)?.response?.status;
      if (status === 401) {
        notifyUnauthorized();
      }
    }
    return Promise.reject(error);
  },
);
