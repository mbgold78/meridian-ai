package com.meridian.substrate.ingestion.readers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads .json files, pretty-prints them, and extracts structural metadata
 * (array vs object, number of top-level elements).
 */
@Component
public class JsonFileReader implements FileReader {

    private final ObjectMapper objectMapper;

    public JsonFileReader() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public String readerId() {
        return "json";
    }

    @Override
    public RawContent read(Path file) throws IOException {
        long size = Files.size(file);
        String rawContent = Files.readString(file);

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(rawContent);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse JSON: " + file, e);
        }

        String prettyPrinted = objectMapper.writeValueAsString(rootNode);

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (rootNode.isArray()) {
            metadata.put("structure", "array");
            metadata.put("topLevelElements", rootNode.size());
        } else if (rootNode.isObject()) {
            metadata.put("structure", "object");
            metadata.put("topLevelElements", rootNode.size());
        } else {
            metadata.put("structure", "scalar");
            metadata.put("topLevelElements", 1);
        }

        return new RawContent(
            file.getFileName().toString(),
            "application/json",
            prettyPrinted,
            size,
            metadata
        );
    }
}
