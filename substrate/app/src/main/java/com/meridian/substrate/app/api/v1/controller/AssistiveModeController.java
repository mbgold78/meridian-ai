package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.engine.ai.assistive.AssistiveModeService;
import com.meridian.substrate.engine.ai.assistive.SuggestRequest;
import com.meridian.substrate.engine.ai.assistive.SuggestResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for Assistive AI mode invocations from inline editing surfaces.
 * Designed for low-latency, frontend-debounced calls; see
 * {@link AssistiveModeService} for the orchestrator-bypass rationale.
 */
@RestController
@RequestMapping("/api/v1/ai/assistive")
public class AssistiveModeController {

    private final AssistiveModeService service;
    private final PersonaContext personaContext;

    public AssistiveModeController(AssistiveModeService service, PersonaContext personaContext) {
        this.service = service;
        this.personaContext = personaContext;
    }

    @PostMapping
    public SuggestResponse suggest(@RequestBody SuggestRequest request) {
        return service.suggest(request, Personas.toEngine(personaContext));
    }
}
