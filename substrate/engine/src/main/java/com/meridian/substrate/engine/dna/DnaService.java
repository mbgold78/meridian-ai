package com.meridian.substrate.engine.dna;

import org.springframework.stereotype.Service;

/**
 * Provides the Project DNA config and composes the DNA preamble that gets
 * prepended to every LLM system prompt.
 */
@Service
public class DnaService {

    private final DnaConfig config;

    public DnaService(DnaConfig config) {
        this.config = config;
    }

    public DnaConfig getConfig() {
        return config;
    }

    /**
     * Compose the DNA preamble injected at the top of every LLM system prompt.
     * Keeps the model grounded in the engagement's specific scope and objective.
     */
    public String composePreamble() {
        return """
                ## Project DNA
                Project: %s
                Domain: %s
                Objective: %s
                Entity types in scope: %s
                Regulatory context: %s
                Reduction mode: %s
                Target horizon: %s months

                Always reason within this scope. When asked about systems, processes, or findings,
                refer to the entities in this domain. Do not invent facts — cite tool results.

                ---
                """.formatted(
                config.getProjectName(),
                config.getDomain(),
                config.getPrimaryObjective(),
                String.join(", ", config.getEntityTypes()),
                String.join(", ", config.getRegulatoryContext()),
                config.getReductionMode(),
                config.getTargetHorizonMonths()
        );
    }
}
