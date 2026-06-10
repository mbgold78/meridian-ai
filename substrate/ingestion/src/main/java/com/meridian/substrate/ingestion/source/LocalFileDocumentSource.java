package com.meridian.substrate.ingestion.source;

import com.meridian.substrate.ingestion.config.IngestionProperties;
import com.meridian.substrate.ingestion.readers.RawContent;
import com.meridian.substrate.ingestion.readers.ReaderRouter;
import com.meridian.substrate.ingestion.readers.RoutedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V1 implementation that reads documents from a local directory
 * (default: {@code substrate.ingestion.samples-dir}).
 * <p>
 * Reader dispatch is delegated to {@link ReaderRouter} (tag-based explicit
 * selection) instead of homepoc's extension-keyed map with its virtual-extension
 * dead ends (port-map §2.4 routing fix).
 */
@Component
public class LocalFileDocumentSource implements DocumentSource {

    private static final Logger log = LoggerFactory.getLogger(LocalFileDocumentSource.class);
    private static final String SOURCE_TYPE = "local-file";

    private final ReaderRouter readerRouter;
    private final Path defaultDirectory;

    public LocalFileDocumentSource(ReaderRouter readerRouter, IngestionProperties properties) {
        this.readerRouter = readerRouter;
        this.defaultDirectory = Paths.get(properties.getSamplesDir());
        log.info("LocalFileDocumentSource initialized with default directory: {}",
                defaultDirectory.toAbsolutePath());
    }

    @Override
    public RawDocument fetch(DocumentRef ref) {
        log.debug("Fetching document: {}", ref.displayName());

        Path filePath = Paths.get(ref.id());
        if (!Files.exists(filePath)) {
            log.error("File not found: {}", filePath);
            return new RawDocument(ref, "", 0L, Map.of("error", "File not found"));
        }

        RoutedDocument routed = readerRouter.route(filePath.getFileName().toString());
        if (!routed.isSupported()) {
            log.warn("No reader for file: {} (tag={})", filePath, routed.tag());
            return new RawDocument(ref, "", 0L,
                    Map.of("error", "No reader for file: " + filePath.getFileName()));
        }

        try {
            RawContent content = routed.reader().read(filePath);
            return new RawDocument(
                ref,
                content.extractedText(),
                content.fileSizeBytes(),
                content.metadata()
            );
        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return new RawDocument(ref, "", 0L,
                    Map.of("error", "Read failed: " + e.getMessage()));
        }
    }

    @Override
    public List<DocumentRef> list(SourceQuery query) {
        Path directory = query.directory() != null && !query.directory().isBlank()
                ? Paths.get(query.directory())
                : defaultDirectory;

        log.debug("Listing documents in directory: {}", directory.toAbsolutePath());

        if (!Files.isDirectory(directory)) {
            log.warn("Directory does not exist: {}", directory.toAbsolutePath());
            return Collections.emptyList();
        }

        Set<String> requestedExtensions = query.extensions() != null
                ? query.extensions().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet())
                : Collections.emptySet();

        List<DocumentRef> refs = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> {
                    String ext = getExtension(path);
                    return requestedExtensions.isEmpty() || requestedExtensions.contains(ext);
                })
                .sorted()
                .forEach(path -> {
                    String id = path.toAbsolutePath().toString();
                    String displayName = path.getFileName().toString();
                    refs.add(new DocumentRef(id, displayName, SOURCE_TYPE));
                });
        } catch (IOException e) {
            log.error("Failed to walk directory: {}", directory, e);
        }

        log.debug("Found {} documents matching query", refs.size());
        return refs;
    }

    private String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
