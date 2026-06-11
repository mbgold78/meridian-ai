package com.meridian.substrate.engine.agent.exception;

/**
 * 404 {@code AGENT_NOT_FOUND} (engine-spec agents.md §7.2 error codes):
 * {@code {idOrKey}} resolved neither as a registration UUID nor as a
 * {@code (domain, agent_key)} slug. The app-module
 * {@code GlobalExceptionHandler} carries the additive mapping.
 */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String message) {
        super(message);
    }
}
