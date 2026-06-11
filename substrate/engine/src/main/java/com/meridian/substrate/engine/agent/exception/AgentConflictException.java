package com.meridian.substrate.engine.agent.exception;

import com.meridian.substrate.engine.agent.read.AgentDetail;

/**
 * 409-conflict family for agent commands (engine-spec agents.md §7.2; ADR-003
 * D1/D10). The app-module {@code GlobalExceptionHandler} carries the additive
 * mapping: {@code AgentConflictException → 409} with the CURRENT
 * {@link AgentDetail} in the envelope {@code data} (the client refreshes —
 * "agent moved on, review current state" — never a generic failure).
 *
 * <p>None of these codes is pause/kill/retire vocabulary: those verbs return
 * HTTP 200 ALWAYS (spec non-negotiable #2 / invariant I5); conflicts arise
 * only from stale fencing tokens, illegal transitions, terminal absorption,
 * duplicate keys, or a missing promotion review.
 */
public class AgentConflictException extends RuntimeException {

    /** Envelope {@code errors[].code} values (spec §7.2). */
    public enum Code {
        /** {@code expectedEventSeq} fencing mismatch (cascade D8 idiom). */
        AGENT_STALE_STATE,
        /** State-machine guard violation (§5.1). */
        AGENT_ILLEGAL_TRANSITION,
        /** RETIRED is terminal-absorbing — any further verb conflicts (§5.1). */
        AGENT_TERMINAL,
        /** Second deploy on the same (domain, agent_key) — uq_agent_domain_key (§5.4). */
        AGENT_KEY_CONFLICT,
        /** Promote without an unconsumed APPROVED PROMOTION review (M4; ADR D10). */
        PROMOTION_REVIEW_REQUIRED
    }

    private final Code code;
    private final transient AgentDetail currentDetail;

    public AgentConflictException(Code code, AgentDetail currentDetail, String message) {
        super(message);
        this.code = code;
        this.currentDetail = currentDetail;
    }

    public Code getCode() {
        return code;
    }

    /**
     * The current state projection for the 409 envelope {@code data}; may be
     * {@code null} when no registration exists or projection was not possible
     * — the conflict envelope is never masked by its own enrichment.
     */
    public AgentDetail getCurrentDetail() {
        return currentDetail;
    }
}
