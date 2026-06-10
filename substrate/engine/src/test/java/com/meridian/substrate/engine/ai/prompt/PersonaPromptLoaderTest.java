package com.meridian.substrate.engine.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the persona-prompt filename fixes (port-map §2.3 / appendix trap 5):
 * all seven known persona levels resolve to a real prompt file — none silently
 * fall back to the default prompt.
 */
class PersonaPromptLoaderTest {

    private static final String DEFAULT_PROMPT_PREFIX = "You are MERIDIAN's Risk Ecosystem Analyst. Help the user";

    private final PersonaPromptLoader loader = new PersonaPromptLoader();

    @Test
    void allSevenPersonaLevels_resolveToRealPromptFiles() {
        // homepoc: data_owner, L3_ANALYST and tech_executive silently fell back.
        assertThat(loader.loadSystemPrompt("architect")).contains("Architect mode");
        assertThat(loader.loadSystemPrompt("auditor")).contains("Auditor mode");
        assertThat(loader.loadSystemPrompt("data_owner")).contains("Data Owner mode");
        assertThat(loader.loadSystemPrompt("executive")).contains("Executive mode");
        assertThat(loader.loadSystemPrompt("sme")).contains("Subject Matter Expert mode");
        assertThat(loader.loadSystemPrompt("tech_executive")).contains("Technology Executive mode");
        assertThat(loader.loadSystemPrompt("L3_ANALYST"))
                .as("L3_ANALYST lowercases to l3_analyst and must load its own file")
                .contains("default analyst persona");
    }

    @Test
    void frontMatterIsStripped() {
        String prompt = loader.loadSystemPrompt("architect");
        assertThat(prompt).doesNotStartWith("---");
        assertThat(prompt).doesNotContain("prompt_id:");
    }

    @Test
    void unknownLevel_fallsBackToDefaultPrompt() {
        assertThat(loader.loadSystemPrompt("no_such_persona"))
                .startsWith(DEFAULT_PROMPT_PREFIX);
    }

    @Test
    void levelLookupIsCaseInsensitive() {
        assertThat(loader.loadSystemPrompt("ARCHITECT"))
                .isEqualTo(loader.loadSystemPrompt("architect"));
    }
}
