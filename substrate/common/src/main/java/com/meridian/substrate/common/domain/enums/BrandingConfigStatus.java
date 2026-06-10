package com.meridian.substrate.common.domain.enums;

/**
 * Lifecycle status for a {@link com.meridian.substrate.common.domain.BrandingConfig}.
 *
 * <p>{@link #DRAFT} configs are authored but not yet applied at app boot.
 * {@link #ACTIVE} is the single config the {@code ThemeProvider} resolves for a
 * given Domain; only one ACTIVE config per Domain is permitted (enforced by a
 * partial unique index in the schema).
 *
 * <p>The status enum is deliberately closed at two values for Phase 2B-1; the
 * lifecycle (versioned drafts, scheduled rollouts, A/B tested branding) lands
 * in later phases without a schema change.
 */
public enum BrandingConfigStatus {
    DRAFT,
    ACTIVE
}
