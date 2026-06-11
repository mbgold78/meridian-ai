import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getDomains } from '@/api/endpoints';
import type { Domain } from '@/shared/types/api';

/**
 * Workspace (engagement domain) resolution.
 *
 * Replaces the homepoc first-domain assumption (`getDomains → data[0]`) with
 * an explicit, deterministic policy. The "workspace" is the Domain every
 * domain-scoped surface (theming, canvas, designers) operates against.
 *
 * Resolution order — first match wins:
 *   1. Deploy-time pin: `VITE_SUBSTRATE_DOMAIN_ID` build env var. Substrate
 *      deploys are per-engagement (one customer, one primary domain), so a
 *      pinned id is the production-normal path. A pin that does not match a
 *      fetched domain is ignored with a console warning (misconfiguration is
 *      surfaced, not silently honored — the id may point at another
 *      environment's data).
 *   2. User selection: localStorage `substrate-workspace-domain-id`, written
 *      by `setWorkspaceDomainId` (a workspace switcher lands in a later
 *      phase). Stale ids (no longer present in the domain list) are ignored.
 *   3. Single domain → that domain.
 *   4. Multiple domains → the first NON-demo domain in server order
 *      (`Domain.demo === false`); demo workspaces never win over a real
 *      engagement domain by accident. If every domain is demo, the first one.
 *   5. No domains → null ("empty" state). A fresh substrate deploy starts
 *      with an empty database (production population flows through
 *      ingestion), so callers must treat empty as a first-run state, not an
 *      error.
 */

export const WORKSPACE_DOMAIN_STORAGE_KEY = 'substrate-workspace-domain-id';

export interface WorkspaceResolutionInputs {
  /** Deploy-time pinned domain id (VITE_SUBSTRATE_DOMAIN_ID). */
  pinnedDomainId?: string | null;
  /** User-selected domain id (localStorage). */
  storedDomainId?: string | null;
}

/** Pure resolution policy — exported for unit testing. */
export function resolveWorkspaceDomain(
  domains: Domain[],
  inputs: WorkspaceResolutionInputs = {},
): Domain | null {
  const { pinnedDomainId, storedDomainId } = inputs;

  if (pinnedDomainId) {
    const pinned = domains.find((d) => d.id === pinnedDomainId);
    if (pinned) return pinned;
    if (domains.length > 0) {
      console.warn(
        `[workspace] VITE_SUBSTRATE_DOMAIN_ID=${pinnedDomainId} does not match any domain; ` +
        'falling through to selection/default resolution.',
      );
    }
  }

  if (storedDomainId) {
    const stored = domains.find((d) => d.id === storedDomainId);
    if (stored) return stored;
  }

  if (domains.length === 0) return null;
  if (domains.length === 1) return domains[0];

  const firstNonDemo = domains.find((d) => !d.demo);
  return firstNonDemo ?? domains[0];
}

/**
 * Deploy-time pinned domain id (VITE_SUBSTRATE_DOMAIN_ID). Exported so
 * demo-biased bindings (the Beacon cascade grid's demo-first resolution)
 * can let an explicit operator pin OVERRIDE their demo preference — the
 * pin's whole purpose is defending against wrong-domain binding.
 */
export function readPinnedDomainId(): string | null {
  const raw = import.meta.env?.VITE_SUBSTRATE_DOMAIN_ID as string | undefined;
  return raw && raw.length > 0 ? raw : null;
}

function readStoredDomainId(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(WORKSPACE_DOMAIN_STORAGE_KEY);
  } catch {
    return null;
  }
}

export type WorkspaceStatus = 'loading' | 'error' | 'empty' | 'ready';

export interface WorkspaceState {
  status: WorkspaceStatus;
  /** Resolved workspace domain; non-null iff status === 'ready'. */
  domain: Domain | null;
  /** Convenience accessor for domain?.id. */
  domainId: string | null;
  /** Full domain list (empty until loaded). */
  domains: Domain[];
  error: unknown;
  /**
   * Persist a user workspace selection (null clears it) and refetch the
   * domain list so dependents re-resolve.
   */
  setWorkspaceDomainId: (domainId: string | null) => void;
}

export interface UseWorkspaceOptions {
  /** Gate the underlying fetch (e.g. when a config is injected in tests). */
  enabled?: boolean;
}

export function useWorkspace(options: UseWorkspaceOptions = {}): WorkspaceState {
  const { enabled = true } = options;
  const queryClient = useQueryClient();

  const domainsQuery = useQuery({
    queryKey: ['workspace', 'domains'],
    queryFn: getDomains,
    enabled,
    staleTime: Infinity,
  });

  const domains = domainsQuery.data?.data ?? [];
  const domain = domainsQuery.data
    ? resolveWorkspaceDomain(domains, {
        pinnedDomainId: readPinnedDomainId(),
        storedDomainId: readStoredDomainId(),
      })
    : null;

  const setWorkspaceDomainId = useCallback(
    (domainId: string | null) => {
      try {
        if (domainId === null) {
          window.localStorage.removeItem(WORKSPACE_DOMAIN_STORAGE_KEY);
        } else {
          window.localStorage.setItem(WORKSPACE_DOMAIN_STORAGE_KEY, domainId);
        }
      } catch {
        /* storage unavailable — selection is session-lost but not fatal */
      }
      void queryClient.invalidateQueries({ queryKey: ['workspace'] });
    },
    [queryClient],
  );

  let status: WorkspaceStatus;
  if (domainsQuery.isError) {
    status = 'error';
  } else if (!domainsQuery.data) {
    status = 'loading';
  } else if (domain === null) {
    status = 'empty';
  } else {
    status = 'ready';
  }

  return {
    status,
    domain,
    domainId: domain?.id ?? null,
    domains,
    error: domainsQuery.error ?? null,
    setWorkspaceDomainId,
  };
}
