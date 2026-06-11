/**
 * Display-only claim extraction for the oidc mode.
 *
 * IMPORTANT SECURITY NOTE: nothing here is authorization. The backend derives
 * the authoritative persona/admin decision from the VERIFIED access token
 * (OidcIdentitySource / BrandingAdminAuthorizer). This module only mirrors the
 * same mapping rules (docs/auth.md §1: `claims.roles` dot-path, ordered
 * `persona.role-map` first-match) so the read-only PersonaSwitcher can show
 * who the user is without an extra round trip.
 */

import type { User } from 'oidc-client-ts';
import type { OidcFrontendConfig } from './config';

type Claims = Record<string, unknown>;

/** Walk a dot-separated path ('realm_access.roles') into a claims object. */
export function claimAtPath(claims: Claims | null | undefined, path: string): unknown {
  if (!claims || !path) return undefined;
  let node: unknown = claims;
  for (const segment of path.split('.')) {
    if (node === null || typeof node !== 'object') return undefined;
    node = (node as Claims)[segment];
  }
  return node;
}

/**
 * Non-validating JWT payload decode, for DISPLAY ONLY (roles often live in
 * the access token — Keycloak's realm_access.roles — not the ID token).
 * Signature verification is the backend's job; we never gate anything on this.
 */
export function decodeJwtPayload(token: string | null | undefined): Claims | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const json = atob(padded);
    const parsed: unknown = JSON.parse(json);
    return parsed !== null && typeof parsed === 'object' ? (parsed as Claims) : null;
  } catch {
    return null;
  }
}

function asRoleList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((v): v is string => typeof v === 'string');
  if (typeof value === 'string' && value.length > 0) return [value];
  return [];
}

/**
 * Roles for the signed-in user: profile (ID token / userinfo) first, then the
 * decoded access-token payload — mirrors where IdPs actually put them.
 */
export function rolesFromUser(user: User, config: OidcFrontendConfig): string[] {
  const path = config.rolesClaimPath ?? 'roles';
  const fromProfile = asRoleList(claimAtPath(user.profile as Claims, path));
  if (fromProfile.length > 0) return fromProfile;
  return asRoleList(claimAtPath(decodeJwtPayload(user.access_token), path));
}

/**
 * Ordered role → persona-level projection, FIRST roleMap entry whose role the
 * user holds wins (same semantics as the backend's persona.role-map; JS object
 * literals preserve string-key insertion order like the YAML LinkedHashMap).
 */
export function personaLevelFromRoles(roles: string[], config: OidcFrontendConfig): string {
  const roleMap = config.roleMap ?? {};
  const held = new Set(roles);
  for (const [role, level] of Object.entries(roleMap)) {
    if (held.has(role)) return level;
  }
  return config.defaultLevel ?? 'L3_ANALYST';
}

export function personaLevelFromUser(user: User, config: OidcFrontendConfig): string {
  return personaLevelFromRoles(rolesFromUser(user, config), config);
}

/**
 * Human display label: configured claim (default `name`) →
 * `preferred_username` → `sub` — the same fallback chain as the backend's
 * OidcIdentitySource display label.
 */
export function displayLabelFromUser(user: User, config: OidcFrontendConfig): string {
  const profile = user.profile as Claims;
  const configured = claimAtPath(profile, config.displayNameClaim ?? 'name');
  if (typeof configured === 'string' && configured.length > 0) return configured;
  const preferred = profile['preferred_username'];
  if (typeof preferred === 'string' && preferred.length > 0) return preferred;
  return user.profile.sub;
}
