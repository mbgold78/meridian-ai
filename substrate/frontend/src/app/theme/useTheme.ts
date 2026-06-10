import { useContext } from 'react';
import { ThemeContext, type ThemeContextValue } from './ThemeContext';

/**
 * Access the active BrandingConfig plus dark-mode state and setters.
 *
 * The returned {@code config} is the full BrandingConfig object from the
 * backend — no subsetting, no computed views. Components read whatever
 * fields they need (asset blob keys, terminology map, email branding,
 * legal URLs) directly.
 *
 * Styling decisions go through CSS custom properties (tokens.css +
 * Tailwind's {@code hsl(var(--primary))}); this hook is for logic
 * decisions (which logo variant to render, whether customCssUrl is set,
 * which terminology to pluralize, etc.).
 *
 * Throws if called outside a ThemeProvider tree.
 */
export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useTheme must be called inside <ThemeProvider>');
  }
  return ctx;
}
