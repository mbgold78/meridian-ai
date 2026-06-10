package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.engine.dna.DnaConfig;
import com.meridian.substrate.engine.dna.DnaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the Project DNA configuration and the composed
 * DNA preamble that is prepended to every LLM system prompt.
 */
@RestController
@RequestMapping("/api/v1/dna")
public class DnaController {

    private final DnaService dnaService;

    public DnaController(DnaService dnaService) {
        this.dnaService = dnaService;
    }

    @GetMapping
    public DnaConfig getDna() {
        return dnaService.getConfig();
    }

    @GetMapping("/preamble")
    public PreambleResponse getPreamble() {
        return new PreambleResponse(dnaService.composePreamble());
    }

    public record PreambleResponse(String preamble) {}
}
