import type { Config } from 'tailwindcss';
import animate from 'tailwindcss-animate';

/**
 * Tailwind config reads color tokens from CSS custom properties defined in
 * {@code src/styles/tokens.css} (and overwritten at runtime by
 * {@link ThemeProvider} from {@code BrandingConfig}). Utility classes like
 * {@code bg-primary}, {@code text-foreground}, {@code border-border} all
 * resolve through {@code hsl(var(--token))} — the {@code <alpha-value>}
 * placeholder makes opacity utilities ({@code bg-primary/50}) work by
 * injecting the alpha channel into the hsl() call.
 *
 * No hardcoded color values belong in this file for new tokens. The legacy
 * {@code meridian} / {@code severity} / {@code category} palettes below are
 * held by the Phase 1 Living Canvas and other pre-Phase-2B surfaces; they
 * will migrate to CSS-var-backed tokens when those surfaces are rewritten.
 * Do NOT add new hardcoded entries alongside them.
 */
const config: Config = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // BrandingConfig-driven tokens — visual foundation + shadcn aliases.
        border: 'hsl(var(--border) / <alpha-value>)',
        'border-strong': 'hsl(var(--border-strong) / <alpha-value>)',
        input: 'hsl(var(--input) / <alpha-value>)',
        ring: 'hsl(var(--ring) / <alpha-value>)',
        background: 'hsl(var(--background) / <alpha-value>)',
        foreground: 'hsl(var(--foreground) / <alpha-value>)',
        'foreground-muted': 'hsl(var(--foreground-muted) / <alpha-value>)',
        'foreground-subtle': 'hsl(var(--foreground-subtle) / <alpha-value>)',
        surface: 'hsl(var(--surface) / <alpha-value>)',
        'surface-raised': 'hsl(var(--surface-raised) / <alpha-value>)',
        primary: {
          DEFAULT: 'hsl(var(--primary) / <alpha-value>)',
          foreground: 'hsl(var(--primary-foreground) / <alpha-value>)',
          hover: 'hsl(var(--primary-hover) / <alpha-value>)',
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary) / <alpha-value>)',
          foreground: 'hsl(var(--secondary-foreground) / <alpha-value>)',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive) / <alpha-value>)',
          foreground: 'hsl(var(--destructive-foreground) / <alpha-value>)',
          surface: 'hsl(var(--destructive-surface) / <alpha-value>)',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted) / <alpha-value>)',
          foreground: 'hsl(var(--muted-foreground) / <alpha-value>)',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent) / <alpha-value>)',
          foreground: 'hsl(var(--accent-foreground) / <alpha-value>)',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover) / <alpha-value>)',
          foreground: 'hsl(var(--popover-foreground) / <alpha-value>)',
        },
        card: {
          DEFAULT: 'hsl(var(--card) / <alpha-value>)',
          foreground: 'hsl(var(--card-foreground) / <alpha-value>)',
        },
        warning: {
          DEFAULT: 'hsl(var(--warning) / <alpha-value>)',
          foreground: 'hsl(var(--warning-foreground) / <alpha-value>)',
          surface: 'hsl(var(--warning-surface) / <alpha-value>)',
        },
        success: {
          DEFAULT: 'hsl(var(--success) / <alpha-value>)',
          foreground: 'hsl(var(--success-foreground) / <alpha-value>)',
          surface: 'hsl(var(--success-surface) / <alpha-value>)',
        },
        info: {
          DEFAULT: 'hsl(var(--info) / <alpha-value>)',
          surface: 'hsl(var(--info-surface) / <alpha-value>)',
        },

        // Industry palette — used for chip + pill accents on Beacon demo
        // surfaces. Backed by --industry-* CSS vars so a future white-label
        // workspace can re-tone industry colors via BrandingConfig.
        'industry-healthcare':      'hsl(var(--industry-healthcare) / <alpha-value>)',
        'industry-government':      'hsl(var(--industry-government) / <alpha-value>)',
        'industry-insurance':       'hsl(var(--industry-insurance) / <alpha-value>)',
        'industry-instrumentation': 'hsl(var(--industry-instrumentation) / <alpha-value>)',
        'industry-financial':       'hsl(var(--industry-financial) / <alpha-value>)',
        'industry-legal':           'hsl(var(--industry-legal) / <alpha-value>)',
        'industry-logistics':       'hsl(var(--industry-logistics) / <alpha-value>)',
        'industry-education':       'hsl(var(--industry-education) / <alpha-value>)',
        'industry-creative':        'hsl(var(--industry-creative) / <alpha-value>)',

        // Lifecycle phase palette — categorical+ordinal, 7 values. Same
        // accent-only application contract as industry-* (2 px left border,
        // never a fill). See docs/design/visual-foundation.md.
        'phase-plug-in':  'hsl(var(--phase-plug-in) / <alpha-value>)',
        'phase-design':   'hsl(var(--phase-design) / <alpha-value>)',
        'phase-map':      'hsl(var(--phase-map) / <alpha-value>)',
        'phase-plan':     'hsl(var(--phase-plan) / <alpha-value>)',
        'phase-execute':  'hsl(var(--phase-execute) / <alpha-value>)',
        'phase-monitor':  'hsl(var(--phase-monitor) / <alpha-value>)',
        'phase-automate': 'hsl(var(--phase-automate) / <alpha-value>)',

        // Legacy palettes — pre-Phase-2B surfaces (Living Canvas etc.).
        // Kept for compat; do not extend. New surfaces use the tokens above.
        meridian: {
          50: '#f0f7ff', 100: '#e0effe', 200: '#bae0fd', 300: '#7cc8fc',
          400: '#36adf8', 500: '#0c93e9', 600: '#0074c7', 700: '#015da1',
          800: '#064f85', 900: '#0b426e', 950: '#072a49',
        },
        severity: {
          critical: '#dc2626', high: '#ea580c', medium: '#d97706',
          low: '#65a30d', info: '#0284c7',
        },
        category: {
          technology: '#7c3aed', organization: '#0891b2', policy: '#4f46e5',
          procedure: '#059669', regulatory: '#dc2626',
        },
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      fontFamily: {
        // Inter Variable for UI / Instrument Serif for editorial / JetBrains Mono.
        // CSS-var-backed; ThemeProvider overrides --font-* from BrandingConfig.
        sans:    ['"Inter Variable"', 'Inter', 'system-ui', 'sans-serif'],
        serif:   ['"Instrument Serif"', 'Georgia', 'serif'],
        heading: ['var(--font-heading)', '"Instrument Serif"', 'Georgia', 'serif'],
        mono:    ['"JetBrains Mono"', 'ui-monospace', 'Menlo', 'monospace'],
      },
      keyframes: {
        // Subtle 0.6 → 1.0 → 0.6 opacity loop for the "live" indicator dot
        // on the Corporate Operating Partner dashboard. Distinct from
        // tailwindcss-animate's `pulse` (which fades to 0.5 and is faster);
        // the foundation doc reserves decorative animation for this single
        // case so the pulse is named explicitly.
        'pulse-soft': {
          '0%, 100%': { opacity: '0.6' },
          '50%':      { opacity: '1' },
        },
      },
      animation: {
        'pulse-soft': 'pulse-soft 2s ease-in-out infinite',
      },
    },
  },
  plugins: [animate],
};

export default config;
