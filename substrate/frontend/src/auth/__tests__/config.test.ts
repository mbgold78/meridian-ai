/**
 * Auth config resolution — Stage 1 did NOT expose the auth mode on
 * GET /api/v1/info, so the SPA resolves it frontend-side:
 * window.__SUBSTRATE_AUTH__ (runtime injection) → VITE_* env → anonymous.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveAuthConfig } from '@/auth/config';

describe('resolveAuthConfig', () => {
  afterEach(() => {
    delete window.__SUBSTRATE_AUTH__;
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('defaults to anonymous when nothing is configured (pre-Phase-5 posture)', () => {
    expect(resolveAuthConfig()).toEqual({ mode: 'anonymous' });
  });

  it('reads runtime-injected window.__SUBSTRATE_AUTH__ oidc config', () => {
    window.__SUBSTRATE_AUTH__ = {
      mode: 'oidc',
      oidc: {
        authority: 'https://idp.customer.example/realms/substrate',
        clientId: 'substrate-spa',
        rolesClaimPath: 'realm_access.roles',
        roleMap: { 'substrate-admin': 'ADMIN' },
      },
    };
    const config = resolveAuthConfig();
    expect(config.mode).toBe('oidc');
    expect(config.oidc).toMatchObject({
      authority: 'https://idp.customer.example/realms/substrate',
      clientId: 'substrate-spa',
      rolesClaimPath: 'realm_access.roles',
      roleMap: { 'substrate-admin': 'ADMIN' },
      // defaults filled in:
      scope: 'openid profile',
      defaultLevel: 'L3_ANALYST',
    });
  });

  it('runtime injection wins over VITE env', () => {
    window.__SUBSTRATE_AUTH__ = { mode: 'anonymous' };
    vi.stubEnv('VITE_SUBSTRATE_AUTH_MODE', 'oidc');
    vi.stubEnv('VITE_OIDC_AUTHORITY', 'https://env.example/realms/x');
    vi.stubEnv('VITE_OIDC_CLIENT_ID', 'env-client');
    expect(resolveAuthConfig().mode).toBe('anonymous');
  });

  it('falls back to VITE env when no runtime config is injected', () => {
    vi.stubEnv('VITE_SUBSTRATE_AUTH_MODE', 'oidc');
    vi.stubEnv('VITE_OIDC_AUTHORITY', 'https://env.example/realms/x');
    vi.stubEnv('VITE_OIDC_CLIENT_ID', 'env-client');
    const config = resolveAuthConfig();
    expect(config.mode).toBe('oidc');
    expect(config.oidc?.authority).toBe('https://env.example/realms/x');
    expect(config.oidc?.clientId).toBe('env-client');
  });

  it('oidc mode without authority/clientId falls back to anonymous with a warning', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    window.__SUBSTRATE_AUTH__ = { mode: 'oidc', oidc: { clientId: 'half-configured' } };
    expect(resolveAuthConfig()).toEqual({ mode: 'anonymous' });
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it('unknown mode strings fall back to anonymous with a warning', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    window.__SUBSTRATE_AUTH__ = { mode: 'saml' };
    expect(resolveAuthConfig()).toEqual({ mode: 'anonymous' });
    expect(warn).toHaveBeenCalledTimes(1);
  });
});
