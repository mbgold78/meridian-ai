package com.meridian.substrate.ingestion.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from {@code classpath:prompts/} and supports
 * variable substitution using {@code {{variable}}} placeholders.
 */
@Service
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Load a prompt template by name (e.g. "convert-csv-systems.md").
     *
     * @param promptName file name under {@code classpath:prompts/}
     * @return the raw template text
     */
    public String loadPrompt(String promptName) {
        return cache.computeIfAbsent(promptName, name -> {
            String resourcePath = "prompts/" + name;
            try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
                String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("Loaded prompt template: {}", resourcePath);
                return template;
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Could not load prompt template: " + resourcePath, e);
            }
        });
    }

    /**
     * Load a prompt template and substitute {@code {{var}}} placeholders with the
     * provided variable values.
     *
     * @param promptName file name under {@code classpath:prompts/}
     * @param variables  map of placeholder names to replacement values
     * @return the resolved prompt text
     */
    public String loadPrompt(String promptName, Map<String, String> variables) {
        String template = loadPrompt(promptName);
        String resolved = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return resolved;
    }
}
