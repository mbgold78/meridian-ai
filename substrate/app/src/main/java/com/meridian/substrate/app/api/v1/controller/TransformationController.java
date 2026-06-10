package com.meridian.substrate.app.api.v1.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Transformation plan endpoint.
 *
 * <p><b>Fixture seam S4 (port-map §5):</b> homepoc served
 * {@code transformation-plan-mockup.json} verbatim with no real plan engine
 * behind it. Substrate ports the endpoint BEHIND A FEATURE FLAG
 * ({@code substrate.transformation.fixture-enabled}, default {@code false})
 * until the real TransformationPlan/TransformationInitiative engine lands
 * (redesign doc 06 schema). With the flag off — the production default — the
 * endpoint returns 404, so no fictional plan data can leak into an engagement
 * build. The fixture lives under {@code classpath:fixtures/} (not the demo
 * pack) because it is controller-served verbatim, not seeded through entities.
 */
@RestController
@RequestMapping("/api/v1/transformation")
public class TransformationController {

    private static final String FIXTURE_LOCATION = "classpath:fixtures/transformation-plan-mockup.json";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final boolean fixtureEnabled;

    public TransformationController(ObjectMapper objectMapper,
                                    ResourceLoader resourceLoader,
                                    @Value("${substrate.transformation.fixture-enabled:false}")
                                    boolean fixtureEnabled) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.fixtureEnabled = fixtureEnabled;
    }

    @GetMapping("/plan")
    public JsonNode getTransformationPlan(@RequestParam(required = false) UUID domainId) {
        if (!fixtureEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Transformation plan engine not available"
                            + " (fixture disabled; set substrate.transformation.fixture-enabled=true for demo use)");
        }
        Resource resource = resourceLoader.getResource(FIXTURE_LOCATION);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transformation plan mockup not found");
        }
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readTree(is);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read transformation plan", e);
        }
    }
}
