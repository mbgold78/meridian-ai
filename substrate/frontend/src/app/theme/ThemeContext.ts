import { createContext } from 'react';
import type { BrandingConfig } from '@/types/branding';

/**
 * Context object + context-value types for the theming foundation.
 *
 * Split out of ThemeProvider.tsx (where homepoc kept them) because
 * react-refresh requires component files to export only components —
 * exporting the context alongside the provider breaks Fast Refresh and
 * fails the lint gate. The public API is unchanged: consume via
 * {@code @/app/theme} (ThemeProvider, useTheme, useTerminology).
 */

/**
 * Color-scheme preference. {@code system} reads
 * {@code prefers-color-scheme} live; {@code light} and {@code dark} pin
 * the mode regardless of OS preference. Persisted to {@code localStorage}
 * under {@code meridian-theme}.
 */
export type ThemePreference = 'system' | 'light' | 'dark';

/** Form of a terminology term rendered by {@code t()}. */
export type TerminologyForm = 'singular' | 'plural';

/**
 * Shape exposed by {@link useTheme} — the full BrandingConfig plus
 * resolved-mode state and setters. The config is the server truth
 * (no computed subset); components read whatever field they need.
 */
export interface ThemeContextValue {
  /** Full active BrandingConfig for the current workspace domain. */
  config: BrandingConfig;
  /** Raw user preference. */
  preference: ThemePreference;
  /** Resolved mode after applying system/light/dark. */
  mode: 'light' | 'dark';
  /** Update the preference; persists to localStorage and re-applies tokens. */
  setPreference: (next: ThemePreference) => void;
  /** Resolve a terminology term; delegates to {@link defaultTerminology} on miss. */
  t: (termKey: string, form?: TerminologyForm) => string;
}

export const ThemeContext = createContext<ThemeContextValue | null>(null);
