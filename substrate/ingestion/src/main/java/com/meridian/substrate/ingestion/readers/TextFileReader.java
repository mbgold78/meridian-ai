package com.meridian.substrate.ingestion.readers;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads .txt and .md files as plain text content.
 * Returns metadata including line count and word count.
 * <p>
 * Registered under reader id "txt"; {@link ReaderRouter} routes both .txt and
 * .md files here when no specialized tag applies.
 */
@Component
public class TextFileReader implements FileReader {

    @Override
    public String readerId() {
        return "txt";
    }

    @Override
    public RawContent read(Path file) throws IOException {
        long size = Files.size(file);
        String content = Files.readString(file);

        String ext = getExtension(file);
        String contentType = "md".equals(ext) ? "text/markdown" : "text/plain";

        long lineCount = content.isEmpty() ? 0 : content.lines().count();
        long wordCount = content.isBlank() ? 0 :
                content.trim().split("\\s+").length;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("lineCount", lineCount);
        metadata.put("wordCount", wordCount);
        metadata.put("fileExtension", ext);

        return new RawContent(
            file.getFileName().toString(),
            contentType,
            content,
            size,
            metadata
        );
    }

    private String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
