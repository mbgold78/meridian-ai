package com.meridian.substrate.engine.diagnostic.exception;

import java.util.UUID;

/**
 * Diagnostic-engine command conflict (engine-spec diagnostic.md §4.2/§5.3/§9/
 * §10.1): HTTP 409 with the spec code in the envelope.
 *
 * <ul>
 *   <li>{@link Code#DIAGNOSTIC_RUN_ACTIVE} — one active diagnostic run per
 *       domain, enforced by the domain-scoped service lock (ADR-004 D11
 *       graft) with the V7_1 Postgres partial-unique index as the CI-verified
 *       backstop.</li>
 *   <li>{@link Code#DRAFT_GROUNDING_LOST} — the spec-named §5.3 code: a
 *       generative draft's cited evidence no longer fully resolves in-domain
 *       at materialization time; the proposal is stale against current
 *       evidence and is NEVER silently materialized.</li>
 *   <li>{@link Code#CURATION_CONFLICT} — a curation verb arrived against a
 *       state it cannot legally act on (accept outside
 *       {@code PENDING_REVIEW/REJECTED}, commit of a REJECTED draft, reject
 *       of an ACCEPTED draft, an idempotency key replayed with a DIFFERENT
 *       action). The append-only history is untouched; the caller re-reads
 *       and decides.</li>
 * </ul>
 *
 * <p>NOT decline/cancel/reject vocabulary: cancel and REJECT return 200
 * always (spec non-negotiable #3); conflicts arise only from racing or
 * contradictory commands.
 */
public class DiagnosticConflictException extends RuntimeException {

    public enum Code {
        DIAGNOSTIC_RUN_ACTIVE,
        DRAFT_GROUNDING_LOST,
        CURATION_CONFLICT
    }

    private final Code code;
    private final UUID domainId;

    public DiagnosticConflictException(Code code, UUID domainId, String message) {
        super(message);
        this.code = code;
        this.domainId = domainId;
    }

    public Code getCode() {
        return code;
    }

    public UUID getDomainId() {
        return domainId;
    }
}
