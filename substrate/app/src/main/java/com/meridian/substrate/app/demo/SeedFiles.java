package com.meridian.substrate.app.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Shared helpers for loading {@code classpath:demopack/*.json} files and reading
 * optional JSON fields. Stateless — consumed by individual seeders.
 */
public final class SeedFiles {

    private SeedFiles() {}

    public static List<JsonNode> load(ObjectMapper mapper, String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return mapper.readValue(is, new TypeReference<>() {});
        }
    }

    public static String textOrNull(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    public static Integer intOrNull(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asInt();
        }
        return null;
    }
}
