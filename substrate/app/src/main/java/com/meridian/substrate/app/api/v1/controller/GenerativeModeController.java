package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.engine.ai.generative.GenerateRequest;
import com.meridian.substrate.engine.ai.generative.GenerateResponse;
import com.meridian.substrate.engine.ai.generative.GenerativeModeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for Generative AI mode invocations from designer surfaces. The
 * service produces DRAFT entities grounded in Universe Evidence; the response
 * carries DTO-only data (entities are not persisted — the caller commits later
 * via the entity-specific CRUD endpoints).
 */
@RestController
@RequestMapping("/api/v1/ai/generative")
public class GenerativeModeController {

    private final GenerativeModeService service;
    private final PersonaContext personaContext;

    public GenerativeModeController(GenerativeModeService service, PersonaContext personaContext) {
        this.service = service;
        this.personaContext = personaContext;
    }

    @PostMapping
    public GenerateResponse generate(@RequestBody GenerateRequest request) {
        return service.generate(request, Personas.toEngine(personaContext));
    }
}
