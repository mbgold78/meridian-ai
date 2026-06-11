package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Wire-status PROJECTION of the two-dimensional agent state (engine-spec
 * agents.md §3/§5.2; ADR-003 D4). Never stored — the DB persists
 * {@link AgentOperationalMode} plus the open-intervention overlay; this enum
 * is derived at read time via {@link #project} and serialized in the EXACT
 * fixture vocabulary.
 *
 * <p>Fixture truth: {@code AgentStatus} union in
 * {@code meridian-homepoc/frontend/src/demo/beacon/types.ts}
 * ({@code 'live' | 'shadow' | 'paused' | 'intervention-required'}, copied
 * 2026-06-11). {@code intervention-required} is the fixture literal — NOT
 * {@code in-intervention} (known port-map prose drift, corrected in ADR-003).
 *
 * <p>{@code RETIRED} is the ADDITIVE fifth value (Mike-flagged, ADR-003 D4 /
 * spec §17 #1): excluded from fleet lists by default because the swapped
 * OperationsTab {@code Record<AgentStatus,…>} pill map crashes on unknown
 * values; reaches the wire only via {@code ?includeRetired=true}.
 *
 * <p>Pinned precedence (§5.2, contract-tested by
 * {@code AgentWireStatusProjectionTest} BEFORE any OperationsTab swap):
 * retired &gt; paused &gt; intervention-required &gt; shadow/live. The
 * precedence is a projector choice over a fixture that conflates mode and
 * attention — events hold the truth; changing it is a projector-only change.
 *
 * <p>No error/failure vocabulary exists in any agent enum by design (spec
 * non-negotiable #2).
 */
public enum AgentWireStatus {
    LIVE,
    SHADOW,
    PAUSED,
    INTERVENTION_REQUIRED,
    RETIRED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * The §5.2 pinned-precedence projection:
     * <pre>
     * retired                if operational_mode = RETIRED
     * paused                 else if operational_mode = PAUSED
     * intervention-required  else if open_intervention_count &gt; 0
     * shadow | live          else operational_mode
     * </pre>
     */
    public static AgentWireStatus project(AgentOperationalMode mode, int openInterventionCount) {
        if (mode == null) {
            throw new IllegalArgumentException("operational mode is required to project a wire status");
        }
        return switch (mode) {
            case RETIRED -> RETIRED;
            case PAUSED -> PAUSED;
            case SHADOW -> openInterventionCount > 0 ? INTERVENTION_REQUIRED : SHADOW;
            case LIVE -> openInterventionCount > 0 ? INTERVENTION_REQUIRED : LIVE;
        };
    }
}
