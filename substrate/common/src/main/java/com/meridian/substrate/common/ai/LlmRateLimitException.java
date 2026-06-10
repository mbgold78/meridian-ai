package com.meridian.substrate.common.ai;

/**
 * Thrown when the LLM provider's rate limit is exceeded.
 * Maps to HTTP 429 Too Many Requests.
 */
public class LlmRateLimitException extends RuntimeException {

    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
