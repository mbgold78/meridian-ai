import axios from 'axios';

export const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Persona header interceptor — placeholder identity source until OIDC lands
// (Phase 5). The X-Persona-Level header is trusted by the backend only in
// anonymous-auth mode (substrate.auth.mode=anonymous). The localStorage key
// is shared with PersonaSwitcher — keep them in sync.
apiClient.interceptors.request.use((config) => {
  const personaLevel = localStorage.getItem('meridian-persona-level') || 'L3_ANALYST';
  config.headers['X-Persona-Level'] = personaLevel;
  return config;
});
