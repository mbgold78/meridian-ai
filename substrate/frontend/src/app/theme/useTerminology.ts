import { useTheme } from './useTheme';
import type { TerminologyForm } from './ThemeContext';

/**
 * Returns the {@code t()} helper for resolving domain-concept terminology.
 *
 * Usage:
 * ```tsx
 * const { t } = useTerminology();
 * <h2>{t('capability', 'plural')}</h2>   // "Capabilities" (default)
 *                                        // "Services" (workspace override)
 * ```
 *
 * Resolution order:
 *   1. Workspace override (BrandingConfig.terminologyOverrides[key][form])
 *   2. DEFAULT_TERMINOLOGY identity map
 *   3. Dev: throw; Prod: capitalize the key
 *
 * No path returns the raw internal key — a renderer that sees the word
 * "capability" in UI text means the key is missing from both the override
 * map and the default map, which is a bug.
 */
export function useTerminology(): {
  t: (termKey: string, form?: TerminologyForm) => string;
} {
  const { t } = useTheme();
  return { t };
}
