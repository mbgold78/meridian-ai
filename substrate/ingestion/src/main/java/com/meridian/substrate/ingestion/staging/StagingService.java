package com.meridian.substrate.ingestion.staging;

import com.meridian.substrate.ingestion.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stages converted output JSON (including the provenance manifest) for human
 * review before commit. Port of homepoc's StagingService with one fix: I/O
 * failures PROPAGATE instead of being swallowed into a zero-count "success"
 * (homepoc returned {@code StagingResult(0, …)} on IOException — a silent
 * zero-yield, port-map §2.4 / no-silent-zero-yield norm).
 */
@Service
public class StagingService {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);

    private final IngestionProperties properties;

    public StagingService(IngestionProperties properties) {
        this.properties = properties;
    }

    /**
     * Copy converted output from {@code outputDir} to the staging area.
     *
     * @throws UncheckedIOException when listing or copying fails
     */
    public StagingResult stage(Path outputDir, Path stagingDir) {
        Path staging = stagingDir != null ? stagingDir : Path.of(properties.getStagingDir());
        log.info("Staging output from {} to {}", outputDir.toAbsolutePath(), staging.toAbsolutePath());
        try {
            Files.createDirectories(staging);
            int count = 0;
            if (Files.isDirectory(outputDir)) {
                try (var stream = Files.list(outputDir)) {
                    for (Path file : stream.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        Files.copy(file, staging.resolve(file.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                        count++;
                        log.debug("Staged: {}", file.getFileName());
                    }
                }
            } else {
                log.warn("Output directory does not exist: {}", outputDir.toAbsolutePath());
            }
            log.info("Staged {} files to {}", count, staging.toAbsolutePath());
            return new StagingResult(count, staging.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Staging failed: " + e.getMessage(), e);
        }
    }

    /** The configured default staging directory. */
    public Path getStagingPath() {
        return Path.of(properties.getStagingDir());
    }
}
