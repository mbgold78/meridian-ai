package com.meridian.substrate.engine.agent.exception;

/**
 * 403 family for agent commands (engine-spec agents.md §7.2; ADR-003 D16).
 * The app-module {@code GlobalExceptionHandler} carries the additive mapping
 * {@code AgentForbiddenException → 403}.
 */
public class AgentForbiddenException extends RuntimeException {

    /** Envelope {@code errors[].code} values. */
    public enum Code {
        /**
         * Lifecycle verbs are REST-only, HUMAN-only; {@code actor_type=AGENT}
         * is illegal on lifecycle events (spec non-negotiable #8 / invariant
         * I11 — service-enforced; no MCP lifecycle tools exist).
         */
        AGENT_LIFECYCLE_HUMAN_ONLY
    }

    private final Code code;

    public AgentForbiddenException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
