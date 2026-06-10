package com.meridian.substrate.app.api;

import com.meridian.substrate.app.api.v1.response.ApiMeta;
import com.meridian.substrate.app.api.v1.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller living inside the {@code com.meridian.substrate.app.api}
 * namespace so the package-scoped {@code ResponseEnvelopeAdvice} applies to it.
 * Used by {@code ResponseEnvelopeAdviceTest}.
 */
@RestController
@RequestMapping("/probe")
public class EnvelopeProbeController {

    public record Payload(String value) {}

    @GetMapping("/plain")
    public Payload plain() {
        return new Payload("hello");
    }

    @GetMapping("/wrapped")
    public ApiResponse<Payload> wrapped() {
        return ApiResponse.ok(new Payload("pre"), ApiMeta.now("pre-wrapped-id", "L1_EXECUTIVE"));
    }
}
