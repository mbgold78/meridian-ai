package com.meridian.substrate.app.api.v1.response;

import java.time.Instant;

/**
 * Metadata included in every API response.
 */
public record ApiMeta(
        Instant timestamp,
        String requestId,
        String version,
        String personaLevel
) {

    private static final String API_VERSION = "0.1.0";

    public static ApiMeta now(String requestId, String personaLevel) {
        return new ApiMeta(Instant.now(), requestId, API_VERSION, personaLevel);
    }
}
