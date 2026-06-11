/// <reference types="vite/client" />

// Auth-mode env flags (Phase 5 Stage 2 — see src/auth/config.ts; the runtime
// window.__SUBSTRATE_AUTH__ injection wins over these at resolution time).
interface ImportMetaEnv {
  readonly VITE_SUBSTRATE_AUTH_MODE?: string;
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
  readonly VITE_OIDC_ROLES_CLAIM_PATH?: string;
  readonly VITE_OIDC_DISPLAY_NAME_CLAIM?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
