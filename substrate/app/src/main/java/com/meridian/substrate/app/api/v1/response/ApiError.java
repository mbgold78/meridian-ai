package com.meridian.substrate.app.api.v1.response;

/**
 * Structured error detail included in error responses.
 */
public record ApiError(
        String code,
        String message,
        String field
) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
