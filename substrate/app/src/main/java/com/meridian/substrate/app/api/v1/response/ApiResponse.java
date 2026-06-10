package com.meridian.substrate.app.api.v1.response;

import java.util.List;

/**
 * Generic envelope wrapping all API responses.
 */
public record ApiResponse<T>(
        T data,
        ApiMeta meta,
        List<ApiError> errors
) {

    public static <T> ApiResponse<T> ok(T data, ApiMeta meta) {
        return new ApiResponse<>(data, meta, List.of());
    }

    public static <T> ApiResponse<T> error(List<ApiError> errors, ApiMeta meta) {
        return new ApiResponse<>(null, meta, errors);
    }
}
