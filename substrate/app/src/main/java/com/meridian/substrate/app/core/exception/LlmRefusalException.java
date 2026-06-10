package com.meridian.substrate.app.core.exception;

/**
 * Thrown when the LLM refuses to answer a request (refusal detected).
 * Maps to HTTP 422 Unprocessable Entity.
 */
public class LlmRefusalException extends RuntimeException {

    public LlmRefusalException(String message) {
        super(message);
    }

    public LlmRefusalException(String message, Throwable cause) {
        super(message, cause);
    }
}
