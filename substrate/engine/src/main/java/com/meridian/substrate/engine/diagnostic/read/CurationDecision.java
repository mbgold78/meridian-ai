package com.meridian.substrate.engine.diagnostic.read;

import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.enums.CurationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of one curation verb (engine-spec diagnostic.md §9/§5.3): the
 * subject, the resulting last-event-wins projection, and the appended
 * append-only event row. {@code replayed=true} means the idempotency key had
 * already been spent — the ORIGINAL result is returned, no new event exists
 * (UNIQUE backstop {@code uq_curation_idem}, spec §3.1).
 *
 * <p>REJECT decisions ride this same record at HTTP 200 — rejection is a
 * product outcome, never error-colored (spec non-negotiable #3).
 */
public record CurationDecision(
        String subjectKind,
        UUID subjectId,
        CurationStatus curationStatus,
        Instant curatedAt,
        String curatedByLabel,
        UUID eventId,
        boolean replayed
) {

    /** Projection of an already-appended event (the replay lane and the draft lanes). */
    public static CurationDecision of(AiCurationEvent event, CurationStatus status, boolean replayed) {
        return new CurationDecision(
                event.getSubjectKind() != null ? event.getSubjectKind().name() : null,
                event.getSubjectId(),
                status,
                event.getCreatedAt(),
                event.getActorLabel(),
                event.getId(),
                replayed);
    }
}
