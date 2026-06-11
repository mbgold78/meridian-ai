package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Dashboard attention signal projected onto a cascade, recomputed on every
 * transition from the frontier (bottom-of-chain) state (engine-spec cascade.md
 * §2; [CASCADE-PROPAGATION]).
 *
 * <p>Derivation: terminal → {@code RESOLVED_LOOKS_GOOD} / {@code RESOLVED_DECLINED};
 * frontier DECLINED → {@code DECLINE_PENDING} (destructive = leader-attention,
 * queue #21 resolved); frontier LOOKING → {@code ESCALATING} (info); frontier
 * ABSORBED → {@code ABSORBED} (success tone, pending originator review). The
 * frontier can never be CASCADED while in-flight (invariant I6). {@code overdue}
 * is a separate boolean overlay flag, never a signal value. No ERROR/FAILED
 * value exists — decline-pending is leader-attention, not failure.
 *
 * <p>DB stores enum names; wire form is kebab-case via {@link JsonValue}
 * ({@code decline-pending}, {@code escalating}, {@code absorbed},
 * {@code resolved-looks-good}, {@code resolved-declined}).
 */
public enum CascadeAttentionSignal {
    DECLINE_PENDING,
    ESCALATING,
    ABSORBED,
    RESOLVED_LOOKS_GOOD,
    RESOLVED_DECLINED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
