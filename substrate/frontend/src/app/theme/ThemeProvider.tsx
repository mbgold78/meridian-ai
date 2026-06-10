import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQuery } from '@tanstack/react-query';
import { getActiveBrandingConfig } from '@/api/endpoints';
import { useWorkspace } from '@/shared/hooks/useWorkspace';
import type { BrandingConfig, TerminologyEntry } from '@/types/branding';
import { applyBrandingToDocument } from './applyBrandingToDocument';
import { DEFAULT_TERMINOLOGY } from './defaultTerminology';
import { DEMO_BRANDING } from './demoBranding';
import {
  ThemeContext,
  type TerminologyForm,
  type ThemeContextValue,
  type ThemePreference,
} from './ThemeContext';

// Context + value types live in ./ThemeContext (react-refresh requires this
// file to export only the component). Re-exported as types for compatibility
// with homepoc-style imports from './ThemeProvider'.
export type { TerminologyForm, ThemeContextValue, ThemePreference };

const THEME_STORAGE_KEY = 'meridian-theme';

function readPreference(): ThemePreference {
  if (typeof window === 'undefined') return 'system';
  const raw = window.localStorage.getItem(THEME_STORAGE_KEY);
  return raw === 'light' || raw === 'dark' || raw === 'system' ? raw : 'system';
}

function resolveMode(pref: ThemePreference): 'light' | 'dark' {
  if (pref !== 'system') return pref;
  if (typeof window === 'undefined') return 'light';
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/**
 * {@code t()} helper: look up a terminology term in the workspace's
 * configured map, fall back to the identity default map, throw in dev
 * mode if both are missing.
 */
function resolveTerm(
  overrides: BrandingConfig['terminologyOverrides'],
  termKey: string,
  form: TerminologyForm,
): string {
  const fromOverride: TerminologyEntry | undefined = overrides?.[termKey];
  if (fromOverride && typeof fromOverride[form] === 'string' && fromOverride[form].length > 0) {
    return fromOverride[form];
  }
  const fromDefault = DEFAULT_TERMINOLOGY[termKey];
  if (fromDefault && fromDefault[form]) {
    return fromDefault[form];
  }
  // Dev: throw so the caller sees the missing key immediately; in prod,
  // fall back to a capitalized form of the key rather than leaking the
  // raw internal identifier into UI text.
  if (import.meta.env?.DEV) {
    throw new Error(
      `useTerminology.t(): no entry for key "${termKey}" (form="${form}"). ` +
      'Add it to DEFAULT_TERMINOLOGY or the workspace override.',
    );
  }
  return termKey.charAt(0).toUpperCase() + termKey.slice(1);
}

/**
 * BrandingConfig resolution chain: resolve the workspace domain (explicit
 * policy in {@link useWorkspace} — deploy pin, then user selection, then
 * non-demo preference; never a blind first-domain grab) → fetch that
 * domain's active branding.
 *
 * Fallback policy (the production decision homepoc deferred):
 * <ul>
 *   <li>Empty workspace (domain list loaded, zero domains): use
 *       {@link DEMO_BRANDING}. Fresh substrate deploys start with an empty
 *       database, so the app must render — the backend would serve the same
 *       transient defaults the moment a domain exists without an ACTIVE
 *       branding row.</li>
 *   <li>Fetch error in DEV: use {@link DEMO_BRANDING} so frontend iteration
 *       does not require a running backend.</li>
 *   <li>Fetch error in production: render the error fallback. Deliberately
 *       hard — a customer deploy must never flash default-Meridian branding
 *       over a real customer config that merely failed to load. The branding
 *       endpoint ships in the first backend increment for exactly this
 *       reason.</li>
 * </ul>
 */
function useBrandingQuery(injectedConfig: BrandingConfig | undefined) {
  const workspace = useWorkspace({ enabled: !injectedConfig });
  const brandingQuery = useQuery({
    queryKey: ['branding', 'active', workspace.domainId],
    queryFn: () => getActiveBrandingConfig(workspace.domainId as string),
    enabled: !injectedConfig && workspace.status === 'ready',
    staleTime: Infinity,
  });
  if (injectedConfig) {
    return { status: 'success' as const, data: injectedConfig, error: null };
  }
  if (workspace.status === 'error') {
    return { status: 'error' as const, data: null, error: workspace.error };
  }
  if (workspace.status === 'empty') {
    return { status: 'success' as const, data: DEMO_BRANDING, error: null };
  }
  if (brandingQuery.isError) {
    return { status: 'error' as const, data: null, error: brandingQuery.error };
  }
  if (brandingQuery.data?.data) {
    return { status: 'success' as const, data: brandingQuery.data.data, error: null };
  }
  return { status: 'loading' as const, data: null, error: null };
}

export interface ThemeProviderProps {
  children: ReactNode;
  /**
   * Minimal fallback rendered while the active BrandingConfig is loading.
   * Keeps the default-Meridian-then-flash UX failure mode off the table:
   * we render nothing branded until the customer's config is in hand.
   */
  loadingFallback?: ReactNode;
  /**
   * Error fallback rendered when the BrandingConfig fetch fails. Default:
   * a minimal unbranded message. Override for custom error UX.
   */
  errorFallback?: (error: unknown) => ReactNode;
  /**
   * Test-only injection — when provided, skips the fetch entirely and uses
   * the given config. Used by the theming integration test to mount the
   * provider with a non-default BrandingConfig without going through the
   * network layer.
   */
  brandingOverride?: BrandingConfig;
}

export function ThemeProvider({
  children,
  loadingFallback = <div aria-busy="true" />,
  errorFallback,
  brandingOverride,
}: ThemeProviderProps) {
  const fetched = useBrandingQuery(brandingOverride);
  // DEV / fixture-demo fallback: if the live BrandingConfig fetch fails (e.g.
  // the backend isn't running while iterating on the frontend), fall back to
  // the bundled default Meridian branding instead of blocking the whole app
  // on an error screen. Production builds (DEV === false) keep the
  // "render nothing branded until config is in hand" UX — see
  // useBrandingQuery's policy doc.
  const branding =
    fetched.status === 'error' && import.meta.env?.DEV
      ? { status: 'success' as const, data: DEMO_BRANDING, error: null }
      : fetched;

  const [preference, setPreferenceState] = useState<ThemePreference>(() => readPreference());
  const [mode, setMode] = useState<'light' | 'dark'>(() => resolveMode(readPreference()));

  // Apply the dark class synchronously before paint so CSS vars in tokens.css
  // resolve to the right mode on the first render pass.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('dark', mode === 'dark');
  }, [mode]);

  // Track system preference live when preference === 'system'.
  useEffect(() => {
    if (preference !== 'system' || typeof window === 'undefined') return;
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!mq) return;
    const onChange = () => setMode(mq.matches ? 'dark' : 'light');
    mq.addEventListener?.('change', onChange);
    return () => mq.removeEventListener?.('change', onChange);
  }, [preference]);

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next);
    setMode(resolveMode(next));
    try { window.localStorage.setItem(THEME_STORAGE_KEY, next); } catch { /* ignore */ }
  }, []);

  // Apply CSS vars to <html> whenever config or mode changes.
  useLayoutEffect(() => {
    if (branding.status !== 'success' || !branding.data) return;
    const cleanup = applyBrandingToDocument(branding.data, { dark: mode === 'dark' });
    return cleanup;
  }, [branding.status, branding.data, mode]);

  const t = useCallback<ThemeContextValue['t']>(
    (termKey, form = 'singular') => {
      const overrides = branding.data?.terminologyOverrides ?? {};
      return resolveTerm(overrides, termKey, form);
    },
    [branding.data],
  );

  const value = useMemo<ThemeContextValue | null>(() => {
    if (branding.status !== 'success' || !branding.data) return null;
    return {
      config: branding.data,
      preference,
      mode,
      setPreference,
      t,
    };
  }, [branding.status, branding.data, preference, mode, setPreference, t]);

  if (branding.status === 'error') {
    return <>{errorFallback ? errorFallback(branding.error) : (
      <div role="alert" style={{ padding: 16 }}>
        Failed to load branding configuration.
      </div>
    )}</>;
  }
  if (!value) {
    return <>{loadingFallback}</>;
  }
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
