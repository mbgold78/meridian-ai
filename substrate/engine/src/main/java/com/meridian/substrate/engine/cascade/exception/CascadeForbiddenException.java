package com.meridian.substrate.engine.cascade.exception;

import java.util.UUID;

/**
 * 403 family for cascade commands (engine-spec cascade.md §5.5/§6.2): the
 * actor is not the party the verb belongs to. Mapped additively to 403 by the
 * app-module {@code GlobalExceptionHandler} in the command-API chunk.
 *
 * <p>Actor identity is PersonaContext-header sourced in Phase 2 — spoofable,
 * accepted per port-map S9; the OIDC principal replaces the source in Phase 5
 * with no schema change (ADR D11).
 */
public class CascadeForbiddenException extends RuntimeException {

    /** Envelope {@code errors[].code} values (spec §6.2). */
    public enum Code {
        /** Level verbs belong to the current owner of the acted depth (§5.3). */
        CASCADE_NOT_LEVEL_OWNER,
        /** RESOLVE_* belongs to the originator only (§5.2 A2/A3). */
        CASCADE_NOT_ORIGINATOR
    }

    private final Code code;
    private final UUID cascadeId;

    public CascadeForbiddenException(Code code, UUID cascadeId, String message) {
        super(message);
        this.code = code;
        this.cascadeId = cascadeId;
    }

    public Code getCode() {
        return code;
    }

    public UUID getCascadeId() {
        return cascadeId;
    }
}
