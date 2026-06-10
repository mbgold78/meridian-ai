package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.engine.ai.validative.ValidateRequest;
import com.meridian.substrate.engine.ai.validative.ValidateResponse;
import com.meridian.substrate.engine.ai.validative.ValidativeModeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for Validative AI mode invocations from designer surfaces. Returns
 * structured Finding candidates as DTOs; the caller decides whether to surface
 * or commit them as persisted Finding entities.
 */
@RestController
@RequestMapping("/api/v1/ai/validative")
public class ValidativeModeController {

    private final ValidativeModeService service;
    private final PersonaContext personaContext;

    public ValidativeModeController(ValidativeModeService service, PersonaContext personaContext) {
        this.service = service;
        this.personaContext = personaContext;
    }

    @PostMapping
    public ValidateResponse validate(@RequestBody ValidateRequest request) {
        return service.validate(request, Personas.toEngine(personaContext));
    }
}
