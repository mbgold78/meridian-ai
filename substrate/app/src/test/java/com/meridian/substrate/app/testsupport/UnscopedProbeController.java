package com.meridian.substrate.app.testsupport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller OUTSIDE the {@code com.meridian.substrate.app.api}
 * namespace. Stands in for springdoc/actuator endpoints: the package-scoped
 * {@code ResponseEnvelopeAdvice} must leave its responses unwrapped
 * (port-map Appendix trap #10).
 */
@RestController
public class UnscopedProbeController {

    public record RawPayload(String value) {}

    @GetMapping("/outside/raw")
    public RawPayload raw() {
        return new RawPayload("raw");
    }
}
