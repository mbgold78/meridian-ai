package com.meridian.substrate.engine.cascade.exception;

import java.util.UUID;

/**
 * 409-conflict family for cascade commands (engine-spec cascade.md §5.5/§6.2).
 *
 * <p>The app-module {@code GlobalExceptionHandler} gains an additive mapping in
 * the command-API chunk: {@code CascadeConflictException → 409} with the
 * current {@code CascadeDetail} in the envelope {@code data} (the UI renders
 * "cascade moved on — review current state", never a generic failure). The
 * cascade id is carried here so the handler can fetch that detail.
 *
 * <p>None of these codes is decline vocabulary: a legitimate DECLINE returns
 * HTTP 200 always (ADR-001 non-negotiable #1); conflicts arise only from
 * stale fencing tokens, illegal transitions, or post-terminal commands.
 */
public class CascadeConflictException extends RuntimeException {

    /** Envelope {@code errors[].code} values (spec §6.2). */
    public enum Code {
        /** {@code expectedChainSeq} fencing mismatch (ADR D8). */
        CASCADE_STALE_STATE,
        /** State-machine guard violation (§5.2/§5.3) incl. payload conditionals I4. */
        CASCADE_ILLEGAL_TRANSITION,
        /** Terminal states are absorbing — any further action conflicts (§5.2). */
        CASCADE_TERMINAL
    }

    private final Code code;
    private final UUID cascadeId;

    public CascadeConflictException(Code code, UUID cascadeId, String message) {
        super(message);
        this.code = code;
        this.cascadeId = cascadeId;
    }

    public Code getCode() {
        return code;
    }

    /** May be {@code null} for draft-anchored conflicts raised before a cascade exists. */
    public UUID getCascadeId() {
        return cascadeId;
    }
}
