package com.meridian.substrate.engine.cascade;

import java.util.UUID;

/**
 * Acting party for a cascade command: soft member reference + denormalized
 * label (ADR-001 D11). In Phase 2 this is mapped from PersonaContext headers
 * at the controller boundary ({@code X-Persona-Id} → {@code memberId} when
 * parseable as a UUID, persona display → {@code label}); the OIDC principal
 * replaces the source in Phase 5 with no schema change.
 *
 * @param memberId nullable soft ref → organization_members (app-enforced, no DB FK)
 * @param label    required render/identity fallback; recorded on every action (I5)
 */
public record CascadeActor(UUID memberId, String label) {

    public CascadeActor {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("actor label is required on every cascade action (invariant I5)");
        }
    }

    public static CascadeActor of(String label) {
        return new CascadeActor(null, label);
    }
}
