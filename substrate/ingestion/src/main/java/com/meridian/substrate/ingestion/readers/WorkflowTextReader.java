package com.meridian.substrate.ingestion.readers;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads plain-text or markdown descriptions of manual workflows (spec Section 11.2).
 * <p>
 * Routed explicitly by {@link ReaderRouter} on tag {@link DocumentTag#WORKFLOW_TEXT}
 * (homepoc registered this reader under the unreachable virtual extension
 * "workflow" — port-map §2.4 routing fix).
 * <p>
 * Extracts workflow-specific metadata by scanning for well-known patterns
 * such as "Frequency:", "Owner:", and "Risk:" in the text content.
 * The full text is returned for downstream LLM parsing into Process entities.
 */
@Component
public class WorkflowTextReader implements FileReader {

    private static final Pattern FREQUENCY_PATTERN =
            Pattern.compile("(?i)^\\s*(?:frequency|cadence)\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern OWNER_PATTERN =
            Pattern.compile("(?i)^\\s*(?:owner|primary[_ ]owner)\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern RISK_PATTERN =
            Pattern.compile("(?i)^\\s*(?:risk|risk[_ ]rating|risk[_ ]level)\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern CLASSIFICATION_PATTERN =
            Pattern.compile("(?i)^\\s*(?:classification|type|category)\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern BUSINESS_LINE_PATTERN =
            Pattern.compile("(?i)^\\s*(?:business[_ ]line|business[_ ]unit|bu)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    @Override
    public String readerId() {
        return "workflow";
    }

    @Override
    public RawContent read(Path file) throws IOException {
        long size = Files.size(file);
        String content = Files.readString(file);

        String ext = getExtension(file);
        String contentType = "md".equals(ext) ? "text/markdown" : "text/plain";

        long lineCount = content.isEmpty() ? 0 : content.lines().count();
        long wordCount = content.isBlank() ? 0 : content.trim().split("\\s+").length;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("readerType", "workflow");
        metadata.put("lineCount", lineCount);
        metadata.put("wordCount", wordCount);
        metadata.put("fileExtension", ext);

        // Extract workflow-specific metadata from text patterns
        extractField(content, FREQUENCY_PATTERN, "extractedFrequency", metadata);
        extractField(content, OWNER_PATTERN, "extractedOwner", metadata);
        extractField(content, RISK_PATTERN, "extractedRisk", metadata);
        extractField(content, CLASSIFICATION_PATTERN, "extractedClassification", metadata);
        extractField(content, BUSINESS_LINE_PATTERN, "extractedBusinessLine", metadata);

        return new RawContent(
            file.getFileName().toString(),
            contentType,
            content,
            size,
            metadata
        );
    }

    private void extractField(String content, Pattern pattern, String metadataKey,
                              Map<String, Object> metadata) {
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            metadata.put(metadataKey, matcher.group(1).trim());
        }
    }

    private String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
