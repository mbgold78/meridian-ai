package com.meridian.substrate.engine.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads persona-specific system prompts from the classpath {@code prompts/} directory.
 * Prompt files are expected at {@code classpath:prompts/analyst-{level}.md} and are
 * cached in memory after first load.
 *
 * <p><b>Port fixes (port-map §2.3 / appendix trap 5):</b>
 * <ul>
 *   <li>{@code data_owner} resolves correctly — the prompt file ships as
 *       {@code analyst-data_owner.md} (homepoc misnamed it {@code analyst-dataowner.md},
 *       making the level silently fall back to the default prompt).</li>
 *   <li>{@code l3_analyst} and {@code tech_executive} now have real prompt files;
 *       previously 3 of 7 levels silently fell back to {@code DEFAULT_PROMPT}.</li>
 *   <li>The fallback is loud: a WARN log fires whenever a level has no prompt
 *       file, instead of an easily-missed INFO.</li>
 * </ul>
 */
@Service
public class PersonaPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PersonaPromptLoader.class);

    private static final String PROMPTS_DIR = "prompts/";
    private static final String DEFAULT_PROMPT =
            "You are MERIDIAN's Risk Ecosystem Analyst. Help the user understand the "
            + "operational risk ecosystem by analyzing systems, integrations, manual processes, "
            + "findings, and design decisions. Always provide evidence-backed answers and cite "
            + "specific entities by name.";

    private final ConcurrentHashMap<String, String> promptCache = new ConcurrentHashMap<>();

    /**
     * Load the system prompt for the given persona level.
     * <p>
     * Looks for a file at {@code classpath:prompts/analyst-{personaLevel}.md}. If
     * the file does not exist, a sensible default prompt is returned (loudly).
     * Loaded prompts are cached for subsequent calls.
     *
     * @param personaLevel the persona level identifier (e.g. "architect", "executive")
     * @return the system prompt text
     */
    public String loadSystemPrompt(String personaLevel) {
        String level = personaLevel != null ? personaLevel.toLowerCase() : "architect";
        return promptCache.computeIfAbsent(level, this::loadFromClasspath);
    }

    private String loadFromClasspath(String level) {
        String resourcePath = PROMPTS_DIR + "analyst-" + level + ".md";
        log.debug("Loading prompt from classpath: {}", resourcePath);

        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.info("Loaded persona prompt [level={}, path={}, length={}]",
                            level, resourcePath, content.length());
                    return stripFrontMatter(content);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read prompt file {}: {}", resourcePath, e.getMessage());
        }

        log.warn("No prompt file found for persona '{}' (expected classpath:{}) — "
                + "falling back to DEFAULT_PROMPT. Add the file to make this persona's "
                + "prompt explicit.", level, resourcePath);
        return DEFAULT_PROMPT;
    }

    /**
     * Strip optional YAML front matter delimited by {@code ---} lines from
     * the beginning of a prompt file.
     *
     * @param content raw file content
     * @return content with front matter removed
     */
    private String stripFrontMatter(String content) {
        if (content.startsWith("---")) {
            int secondDelimiter = content.indexOf("---", 3);
            if (secondDelimiter > 0) {
                return content.substring(secondDelimiter + 3).trim();
            }
        }
        return content.trim();
    }
}
