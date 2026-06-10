package com.meridian.substrate.common.ai;

/**
 * Thrown when an LLM call is attempted while the client is disabled —
 * typically because no API key is configured (degraded mode). The
 * application boots normally without a key; only LLM-backed calls fail.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
