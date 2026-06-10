import type { TerminologyMap } from '@/types/branding';

/**
 * Fallback terminology map — mirrors the identity map seeded by
 * BrandingDefaults.defaultTerminology on the backend. The t() helper falls
 * back to this when a workspace's configured map is missing a key, so the
 * UI never renders a raw internal identifier ("capability") as user-facing
 * text.
 *
 * Stays in sync with
 * {@code app/src/main/java/com/meridian/substrate/app/branding/BrandingDefaults.java}.
 * Add a key here whenever a new key is added on the backend.
 */
export const DEFAULT_TERMINOLOGY: TerminologyMap = {
  capability: { singular: 'Capability', plural: 'Capabilities' },
  process:    { singular: 'Process',    plural: 'Processes' },
  domain:     { singular: 'Domain',     plural: 'Domains' },
  finding:    { singular: 'Finding',    plural: 'Findings' },
  system:     { singular: 'System',     plural: 'Systems' },
  reader:     { singular: 'Reader',     plural: 'Readers' },
};
