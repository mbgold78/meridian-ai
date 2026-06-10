package com.meridian.substrate.common.domain.enums;

/**
 * Seven lifecycle phases from docs/redesign/02. Six are sequenced
 * (Design → Monitor → Automate); PLUG_IN is continuous.
 */
public enum LifecyclePhase {
    PLUG_IN,
    DESIGN,
    MAP,
    PLAN,
    EXECUTE,
    MONITOR,
    AUTOMATE
}
