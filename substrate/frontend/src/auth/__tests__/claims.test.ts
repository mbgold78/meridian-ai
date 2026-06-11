/**
 * Display-only claim extraction — mirrors the backend's configurable mapping
 * (docs/auth.md §1: claims.roles dot-path; persona.role-map ordered
 * first-match; display label name → preferred_username → sub).
 */
import { describe, expect, it } from 'vitest';
import type { User } from 'oidc-client-ts';
import {
  claimAtPath,
  decodeJwtPayload,
  displayLabelFromUser,
  personaLevelFromRoles,
  personaLevelFromUser,
  rolesFromUser,
} from '@/auth/claims';
import type { OidcFrontendConfig } from '@/auth/config';

const BASE_CONFIG: OidcFrontendConfig = {
  authority: 'https://idp.example.test/realms/substrate',
  clientId: 'substrate-spa',
  rolesClaimPath: 'realm_access.roles',
  roleMap: { 'substrate-admin': 'ADMIN', 'substrate-coo': 'EXECUTIVE' },
  defaultLevel: 'L3_ANALYST',
};

function fakeUser(profile: Record<string, unknown>, accessToken = 'opaque'): User {
  return { access_token: accessToken, expired: false, profile } as unknown as User;
}

/** Build an unsigned JWT-shaped token with the given payload (display-only decode). */
function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (obj: object) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}

describe('claimAtPath', () => {
  it('walks dot-separated paths', () => {
    expect(claimAtPath({ realm_access: { roles: ['a'] } }, 'realm_access.roles')).toEqual(['a']);
  });

  it('returns undefined for missing segments and non-object nodes', () => {
    expect(claimAtPath({ realm_access: 'flat' }, 'realm_access.roles')).toBeUndefined();
    expect(claimAtPath({}, 'nope')).toBeUndefined();
    expect(claimAtPath(null, 'x')).toBeUndefined();
  });
});

describe('decodeJwtPayload', () => {
  it('decodes a base64url JWT payload', () => {
    expect(decodeJwtPayload(fakeJwt({ sub: 'u-1', roles: ['r'] }))).toEqual({
      sub: 'u-1',
      roles: ['r'],
    });
  });

  it('returns null for opaque/garbage tokens', () => {
    expect(decodeJwtPayload('opaque-token')).toBeNull();
    expect(decodeJwtPayload('a.%%%%.c')).toBeNull();
    expect(decodeJwtPayload(null)).toBeNull();
  });
});

describe('rolesFromUser', () => {
  it('prefers roles found in the profile claims', () => {
    const user = fakeUser({ sub: 'u', realm_access: { roles: ['substrate-coo'] } });
    expect(rolesFromUser(user, BASE_CONFIG)).toEqual(['substrate-coo']);
  });

  it('falls back to the decoded access token when the profile lacks roles (Keycloak shape)', () => {
    const token = fakeJwt({ sub: 'u', realm_access: { roles: ['substrate-admin'] } });
    const user = fakeUser({ sub: 'u' }, token);
    expect(rolesFromUser(user, BASE_CONFIG)).toEqual(['substrate-admin']);
  });

  it('accepts a single-string role claim', () => {
    const user = fakeUser({ sub: 'u', realm_access: { roles: 'substrate-admin' } });
    expect(rolesFromUser(user, BASE_CONFIG)).toEqual(['substrate-admin']);
  });
});

describe('personaLevelFromRoles', () => {
  it('first roleMap entry whose role is held wins (ordered, like backend persona.role-map)', () => {
    expect(personaLevelFromRoles(['substrate-coo', 'substrate-admin'], BASE_CONFIG)).toBe('ADMIN');
  });

  it('falls back to defaultLevel when nothing matches', () => {
    expect(personaLevelFromRoles(['unmapped'], BASE_CONFIG)).toBe('L3_ANALYST');
    expect(personaLevelFromRoles([], BASE_CONFIG)).toBe('L3_ANALYST');
  });

  it('derives via the full user path', () => {
    const user = fakeUser({ sub: 'u', realm_access: { roles: ['substrate-coo'] } });
    expect(personaLevelFromUser(user, BASE_CONFIG)).toBe('EXECUTIVE');
  });
});

describe('displayLabelFromUser', () => {
  it('uses the configured display-name claim first', () => {
    const user = fakeUser({ sub: 'u-1', name: 'Dev Admin', preferred_username: 'dev-admin' });
    expect(displayLabelFromUser(user, BASE_CONFIG)).toBe('Dev Admin');
  });

  it('falls back name → preferred_username → sub (backend OidcIdentitySource chain)', () => {
    expect(displayLabelFromUser(fakeUser({ sub: 'u-1', preferred_username: 'dev-admin' }), BASE_CONFIG)).toBe(
      'dev-admin',
    );
    expect(displayLabelFromUser(fakeUser({ sub: 'u-1' }), BASE_CONFIG)).toBe('u-1');
  });
});
