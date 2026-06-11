package com.meridian.substrate.engine.contract.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The ADR D4 mode ladder, {@code substrate.contracts.enforcement.mode}
 * (engine-spec contracts.md §1): {@code off} — gateway skipped (counter
 * only); {@code shadow} — full evaluation, would-be denials rowed
 * {@code WOULD_DENY}, nothing blocked; {@code enforce} — DENY blocks.
 * Per-engagement; read at evaluate time so an engagement can flip without a
 * restart.
 */
public enum EnforcementMode {
    OFF, SHADOW, ENFORCE;

    private static final Logger log = LoggerFactory.getLogger(EnforcementMode.class);

    /**
     * Lenient parse: an unrecognized value falls back to SHADOW (the §1
     * default and the ADR D4 ship posture — full evaluation, nothing
     * blocked), WARN-logged. A config typo must neither silently disable
     * evaluation (off) nor silently start blocking (enforce).
     */
    public static EnforcementMode parse(String raw) {
        if (raw != null) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off":
                    return OFF;
                case "shadow":
                    return SHADOW;
                case "enforce":
                    return ENFORCE;
                default:
                    // fall through to the WARN below
            }
        }
        log.warn("Unrecognized substrate.contracts.enforcement.mode '{}' — falling back to"
                + " shadow (ADR D4 default; evaluates fully, blocks nothing)", raw);
        return SHADOW;
    }

    /** The D17 {@code mode} column spelling ('shadow' | 'enforce'). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
