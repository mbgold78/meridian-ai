import type { BrandingConfig } from '@/types/branding';

/**
 * Apply a {@link BrandingConfig} to {@code document.documentElement} by
 * writing each token into a CSS custom property. Called by ThemeProvider
 * once the active config has loaded and whenever the dark-mode class flips.
 *
 * <ul>
 *   <li>Light tokens (colorTokensLight) are always applied at :root scope.</li>
 *   <li>Dark tokens (colorTokensDark) are applied only when the {@code .dark}
 *       class is present on {@code <html>} — writing them inline would
 *       override the CSS {@code .dark} rules regardless of current mode.
 *       Instead we set them when the user flips dark mode on, and remove
 *       them (restoring the :root light value) when they flip back.</li>
 *   <li>Font family tokens overwrite --font-body / --font-heading /
 *       --font-mono so custom Google / self-hosted fonts take effect.</li>
 * </ul>
 *
 * Returns a cleanup function that restores the pre-apply state, useful for
 * tests that mount multiple providers in a single JSDOM instance.
 */
export function applyBrandingToDocument(
  config: BrandingConfig,
  options: { dark: boolean },
): () => void {
  const html = document.documentElement;
  const previous: Record<string, string | null> = {};

  const write = (name: string, value: string) => {
    previous[name] = html.style.getPropertyValue(name) || null;
    html.style.setProperty(name, value);
  };

  const tokens = options.dark ? config.colorTokensDark : config.colorTokensLight;
  for (const [name, value] of Object.entries(tokens)) {
    write(`--${name}`, value);
  }

  // Font families — written unconditionally so light/dark mode use the same
  // typography (expected). If a customer later wants per-mode typography
  // they add fontBodyDark/etc. and the condition lands here.
  write('--font-body', config.fontBody);
  write('--font-heading', config.fontHeading);
  write('--font-mono', config.fontMono);

  // Optional scale overrides
  for (const [group, prefix] of [
    [config.spacingTokens, 'spacing'],
    [config.radiusTokens,  'radius'],
    [config.shadowTokens,  'shadow'],
  ] as const) {
    if (!group) continue;
    for (const [name, value] of Object.entries(group)) {
      write(`--${prefix}-${name}`, value);
    }
  }

  return () => {
    for (const [name, value] of Object.entries(previous)) {
      if (value === null) {
        html.style.removeProperty(name);
      } else {
        html.style.setProperty(name, value);
      }
    }
  };
}
