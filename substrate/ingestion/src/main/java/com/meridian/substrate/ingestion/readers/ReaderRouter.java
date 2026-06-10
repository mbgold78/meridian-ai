package com.meridian.substrate.ingestion.readers;

import com.meridian.substrate.ingestion.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Explicit document routing: classifies a file into a {@link DocumentTag} via
 * the configured first-match-wins rules (falling back to plain extension
 * dispatch), then selects the reader and prompt template for that tag.
 *
 * <p>This replaces homepoc's two broken mechanisms in one place (port-map §2.4):
 * <ul>
 *   <li>the virtual-extension reader map, which made ManualProcessCsvReader and
 *       WorkflowTextReader unreachable dead code;</li>
 *   <li>the separate compile-time PROMPT_MAPPINGS table inside ConversionPipeline.</li>
 * </ul>
 */
@Component
public class ReaderRouter {

    private static final Logger log = LoggerFactory.getLogger(ReaderRouter.class);

    private final Map<String, FileReader> readersById;
    private final IngestionProperties properties;

    public ReaderRouter(List<FileReader> readers, IngestionProperties properties) {
        this.readersById = readers.stream()
                .collect(Collectors.toMap(FileReader::readerId, Function.identity()));
        this.properties = properties;
        log.info("ReaderRouter initialized with readers: {}", readersById.keySet());
    }

    /**
     * Route a file by name: tag classification + reader selection.
     */
    public RoutedDocument route(String fileName) {
        DocumentTag tag = classify(fileName);
        FileReader reader = readerFor(tag);
        return new RoutedDocument(tag, reader);
    }

    /**
     * Classify a file name into a document tag using the configured rules
     * (first match wins), falling back to plain extension dispatch.
     */
    public DocumentTag classify(String fileName) {
        String lowerName = fileName.toLowerCase();
        String extension = extractExtension(lowerName);

        for (IngestionProperties.RoutingRule rule : properties.getRoutingRules()) {
            if (rule.getPattern() != null && rule.getTag() != null
                    && lowerName.contains(rule.getPattern().toLowerCase())
                    && (rule.getExtension() == null || rule.getExtension().isEmpty()
                        || extension.equals(rule.getExtension().toLowerCase()))) {
                log.debug("Matched routing rule '{}'/{} → {} for file '{}'",
                        rule.getPattern(), rule.getExtension(), rule.getTag(), fileName);
                return rule.getTag();
            }
        }

        return switch (extension) {
            case "csv" -> DocumentTag.GENERIC_CSV;
            case "json" -> DocumentTag.GENERIC_JSON;
            case "txt", "md" -> DocumentTag.GENERIC_TEXT;
            default -> DocumentTag.UNSUPPORTED;
        };
    }

    /**
     * The reader handling documents of the given tag (null for UNSUPPORTED).
     */
    public FileReader readerFor(DocumentTag tag) {
        String readerId = switch (tag) {
            case MANUAL_PROCESS_CSV -> "manual-process-csv";
            case WORKFLOW_TEXT -> "workflow";
            case SYSTEM_CSV, ENTITY_CSV, GENERIC_CSV -> "csv";
            case GENERIC_JSON -> "json";
            case GENERIC_TEXT -> "txt";
            case UNSUPPORTED -> null;
        };
        return readerId == null ? null : readersById.get(readerId);
    }

    /**
     * The prompt template used to LLM-extract documents of the given tag,
     * or null when no template applies (UNSUPPORTED).
     */
    public String promptTemplateFor(DocumentTag tag) {
        return properties.getPromptTemplates().get(tag);
    }

    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
